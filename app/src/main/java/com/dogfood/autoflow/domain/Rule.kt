package com.dogfood.autoflow.domain

/** Immutable identifier for a rule. */
@JvmInline
value class RuleId(val raw: String)

/** A signal emitted into the engine. Triggers match against these. */
data class Event(
    val type: String,
    val payload: Map<String, String> = emptyMap(),
    val timestampMs: Long = 0L,
)

/** Triggers decide whether an incoming [Event] can start a rule. Extensible via the registry. */
sealed interface Trigger {
    fun matches(event: Event): Boolean
}

data class EventTypeTrigger(val type: String) : Trigger {
    override fun matches(event: Event): Boolean = event.type == type
}

data class PayloadEqualsTrigger(val key: String, val value: String) : Trigger {
    override fun matches(event: Event): Boolean = event.payload[key] == value
}

data class TimeWindowTrigger(val startTime: String, val endTime: String) : Trigger {
    override fun matches(event: Event): Boolean {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = event.timestampMs
        val currentTime = String.format(
            "%02d:%02d",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE)
        )
        return currentTime >= startTime && currentTime <= endTime
    }
}

/** Conditions form a boolean tree evaluated against an [EvalContext]. */
sealed interface Condition {
    fun evaluate(ctx: EvalContext): Boolean
}

data object AlwaysTrue : Condition {
    override fun evaluate(ctx: EvalContext): Boolean = true
}

data class StateEquals(val key: String, val value: String) : Condition {
    override fun evaluate(ctx: EvalContext): Boolean = ctx.state[key] == value
}

data class Numeric(val key: String, val op: Op, val bound: Double) : Condition {
    enum class Op { LT, LE, EQ, GE, GT }

    override fun evaluate(ctx: EvalContext): Boolean {
        val v = ctx.state[key]?.toDoubleOrNull() ?: return false
        return when (op) {
            Op.LT -> v < bound
            Op.LE -> v <= bound
            Op.EQ -> v == bound
            Op.GE -> v >= bound
            Op.GT -> v > bound
        }
    }
}

data class And(val children: List<Condition>) : Condition {
    override fun evaluate(ctx: EvalContext): Boolean = children.all { it.evaluate(ctx) }
}

data class Or(val children: List<Condition>) : Condition {
    override fun evaluate(ctx: EvalContext): Boolean = children.any { it.evaluate(ctx) }
}

data class Not(val child: Condition) : Condition {
    override fun evaluate(ctx: EvalContext): Boolean = !child.evaluate(ctx)
}

/** Actions are side effects the host interprets. The engine only records intents. */
sealed interface Action {
    val label: String
}

data class SetState(val key: String, val value: String) : Action {
    override val label: String get() = "set $key=$value"
}

data class Emit(val event: Event) : Action {
    override val label: String get() = "emit ${event.type}"
}

data class Notify(val message: String) : Action {
    override val label: String get() = "notify: $message"
}

/** A rule binds a trigger, a guard condition and an ordered action list. */
data class Rule(
    val id: RuleId,
    val name: String,
    val trigger: Trigger,
    val condition: Condition = AlwaysTrue,
    val actions: List<Action>,
    val enabled: Boolean = true,
)

/** Read-only evaluation context. The engine produces new snapshots on write. */
data class EvalContext(val state: Map<String, String>)
