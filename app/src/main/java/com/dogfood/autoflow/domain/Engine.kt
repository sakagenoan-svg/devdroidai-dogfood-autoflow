package com.dogfood.autoflow.domain

/**
 * Plugin registry: new trigger/action kinds can be registered without touching the engine.
 * Specs build domain objects from a serialized form (kind + string args), which is how
 * persisted/remote rules are rehydrated.
 */
class Registry private constructor(
    private val triggers: Map<String, TriggerSpec>,
    private val actions: Map<String, ActionSpec>,
) {
    fun buildTrigger(kind: String, args: Map<String, String>): Trigger =
        (triggers[kind] ?: error("unknown trigger kind: $kind")).build(args)

    fun buildAction(kind: String, args: Map<String, String>): Action =
        (actions[kind] ?: error("unknown action kind: $kind")).build(args)

    val triggerKinds: Set<String> get() = triggers.keys
    val actionKinds: Set<String> get() = actions.keys

    class Builder {
        private val triggers = mutableMapOf<String, TriggerSpec>()
        private val actions = mutableMapOf<String, ActionSpec>()

        fun trigger(kind: String, spec: TriggerSpec) = apply { triggers[kind] = spec }
        fun action(kind: String, spec: ActionSpec) = apply { actions[kind] = spec }
        fun build() = Registry(triggers.toMap(), actions.toMap())
    }

    companion object {
        /** Default registry wiring the built-in kinds. Add new kinds here or via [Builder]. */
        fun default(): Registry = Builder()
            .trigger("event_type") { args -> EventTypeTrigger(args.getValue("type")) }
            .trigger("payload_equals") { args -> PayloadEqualsTrigger(args.getValue("key"), args.getValue("value")) }
            .action("set_state") { args -> SetState(args.getValue("key"), args.getValue("value")) }
            .action("notify") { args -> Notify(args.getValue("message")) }
            .build()
    }
}

fun interface TriggerSpec {
    fun build(args: Map<String, String>): Trigger
}

fun interface ActionSpec {
    fun build(args: Map<String, String>): Action
}

/** Result of dispatching one event through the engine. */
data class Dispatch(
    val firedRuleIds: List<RuleId>,
    val performed: List<Action>,
    val newState: Map<String, String>,
)

/**
 * The engine holds an ordered rule set. For each incoming event it evaluates every enabled
 * rule (trigger match -> condition guard) and folds the matching rules' actions into a new
 * immutable state snapshot.
 */
class RuleEngine(initialRules: List<Rule> = emptyList()) {

    private val rules = LinkedHashMap<RuleId, Rule>().apply {
        initialRules.forEach { put(it.id, it) }
    }

    val all: List<Rule> get() = rules.values.toList()

    fun upsert(rule: Rule) {
        rules[rule.id] = rule
    }

    fun remove(id: RuleId) {
        rules.remove(id)
    }

    fun setEnabled(id: RuleId, enabled: Boolean) {
        rules[id]?.let { rules[id] = it.copy(enabled = enabled) }
    }

    fun dispatch(event: Event, state: Map<String, String>): Dispatch {
        var working = state
        val fired = mutableListOf<RuleId>()
        val performed = mutableListOf<Action>()
        for (rule in rules.values) {
            if (!rule.enabled) continue
            if (!rule.trigger.matches(event)) continue
            if (!rule.condition.evaluate(EvalContext(working))) continue
            fired += rule.id
            for (action in rule.actions) {
                performed += action
                if (action is SetState) {
                    working = working + (action.key to action.value)
                }
            }
        }
        return Dispatch(fired, performed, working)
    }
}
