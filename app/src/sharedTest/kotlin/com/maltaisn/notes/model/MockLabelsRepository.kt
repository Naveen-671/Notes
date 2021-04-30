/*
 * Copyright 2021 Nicolas Maltais
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.maltaisn.notes.model

import com.maltaisn.notes.model.entity.Label
import com.maltaisn.notes.model.entity.LabelRef
import com.maltaisn.notes.model.entity.Note
import com.maltaisn.notes.model.entity.NoteWithLabels
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

/**
 * Implementation of the labels repository that stores data itself instead of relying on DAOs.
 * This implementation should work almost exactly like [DefaultLabelsRepository].
 */
class MockLabelsRepository : LabelsRepository {

    private val labels = mutableMapOf<Long, Label>()

    private val labelRefs = mutableMapOf<Long, MutableSet<Long>>()

    var lastLabelId = 0L
        private set

    val changeFlow = MutableSharedFlow<Unit>(replay = 1)
    val refsChangeFlow = MutableSharedFlow<Unit>(replay = 1)

    /**
     * Add label without notifying change flow.
     */
    private fun addLabelInternal(label: Label): Long {
        val id = if (label.id != Label.NO_ID) {
            labels[label.id] = label
            if (label.id > lastLabelId) {
                lastLabelId = label.id
            }
            label.id
        } else {
            lastLabelId++
            labels[lastLabelId] = label.copy(id = lastLabelId)
            lastLabelId
        }
        return id
    }

    /** Non-suspending version of [insertLabel]. */
    fun addLabel(label: Label): Long {
        val id = addLabelInternal(label)
        changeFlow.tryEmit(Unit)
        return id
    }

    override suspend fun insertLabel(label: Label): Long {
        val id = addLabel(label)
        changeFlow.emit(Unit)
        return id
    }

    override suspend fun updateLabel(label: Label) {
        require(label.id in labels) { "Cannot update non-existent label" }
        insertLabel(label)
    }

    private fun deleteLabelInternal(id: Long): Boolean {
        var refsChanged = false
        labels -= id
        for (refs in labelRefs.values) {
            if (refs.remove(id)) {
                refsChanged = true
            }
        }
        return refsChanged
    }

    override suspend fun deleteLabel(label: Label) {
        if (deleteLabelInternal(label.id)) {
            refsChangeFlow.emit(Unit)
        }
        changeFlow.emit(Unit)
    }

    override suspend fun deleteLabels(labels: List<Label>) {
        var refsChanged = false
        for (label in labels) {
            refsChanged = refsChanged or deleteLabelInternal(label.id)
        }
        if (refsChanged) {
            refsChangeFlow.emit(Unit)
        }
        changeFlow.emit(Unit)
    }

    override suspend fun getLabelById(id: Long) = labels[id]

    fun requireLabelById(id: Long) = labels.getOrElse(id) {
        error("No label with ID $id")
    }

    override suspend fun getLabelByName(name: String) = labels.values.find { it.name == name }

    override suspend fun insertLabelRefs(refs: List<LabelRef>) {
        addLabelRefs(refs)
    }

    override suspend fun deleteLabelRefs(refs: List<LabelRef>) {
        for (ref in refs) {
            labelRefs[ref.noteId]?.remove(ref.labelId)
        }
        refsChangeFlow.emit(Unit)
    }

    override suspend fun getLabelIdsForNote(noteId: Long) =
        labelRefs[noteId].orEmpty().toList()

    fun getNotesForLabelId(labelId: Long) =
        labelRefs.asSequence()
            .filter { (_, labelIds) -> labelId in labelIds }
            .map { (id, _) -> id }
            .toList()

    fun getNoteWithLabels(note: Note) = NoteWithLabels(note,
        labelRefs[note.id].orEmpty().map { requireLabelById(it) })

    // Non suspending version for initialization
    fun addLabelRefs(refs: List<LabelRef>) {
        for (ref in refs) {
            labelRefs.getOrPut(ref.noteId) { mutableSetOf() } += ref.labelId
        }
        refsChangeFlow.tryEmit(Unit)
    }

    override suspend fun countLabelRefs(labelId: Long) =
        labelRefs.values.sumBy { labels -> labels.count { it == labelId } }.toLong()

    override suspend fun clearAllData() {
        labels.clear()
        labelRefs.clear()
        lastLabelId = 0
        changeFlow.emit(Unit)
        refsChangeFlow.emit(Unit)
    }

    suspend fun clearAllLabelRefs() {
        labelRefs.clear()
        refsChangeFlow.emit(Unit)
    }

    override fun getAllLabels() = changeFlow.map {
        labels.values.toList()
    }

}
