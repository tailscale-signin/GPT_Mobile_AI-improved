package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

/** Operational metadata only: no task, model label, evidence, path or exception message. */
internal object AssistTelemetry {
    private fun code(value: String?): String = value?.takeIf { it.length <= 100 && it.matches(Regex("[A-Za-z0-9:_-]+")) } ?: "UNSPECIFIED"

    internal fun transition(runId: String, task: AssistTaskState): String =
        "TRANSITION · run=${code(runId)} · task=${code(task.taskId)} · sequence=${task.sequence} · admission=${task.admission} · execution=${task.execution} · delivery=${task.delivery} · reason=${code(task.reason)} · attempts=${task.physicalAttempts} · producer=${code(task.producerInvocationId)} · selected=${task.selectedSpanIds.size} · examined=${task.examinedSpanIds.size} · unprocessed=${task.unprocessedSpanIds.size}"

    fun record(runId: String, task: AssistTaskState) {
        AppLogRecorder.record("LocalAssist", transition(runId, task))
    }

    fun handoff(runId: String?, consumer: String?, attempt: String?, offered: Set<String>, included: Set<String>) {
        offered.forEach { producer ->
            AppLogRecorder.record("LocalAssist", "HANDOFF · run=${code(runId)} · consumer=${code(consumer)} · attempt=${code(attempt)} · producer=${code(producer)} · delivery=${if (producer in included) AssistDelivery.INCLUDED_IN_REQUEST else AssistDelivery.NOT_INCLUDED} · boundary=SERIALIZED_REQUEST")
        }
    }
}
