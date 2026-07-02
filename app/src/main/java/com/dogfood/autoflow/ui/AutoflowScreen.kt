package com.dogfood.autoflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dogfood.autoflow.domain.Event
import com.dogfood.autoflow.domain.Notify
import com.dogfood.autoflow.domain.Numeric
import com.dogfood.autoflow.domain.PayloadEqualsTrigger
import com.dogfood.autoflow.domain.Rule
import com.dogfood.autoflow.domain.RuleEngine
import com.dogfood.autoflow.domain.RuleId
import com.dogfood.autoflow.domain.EventTypeTrigger
import com.dogfood.autoflow.domain.SetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AutoflowUiState(
    val rules: List<Rule> = emptyList(),
    val state: Map<String, String> = emptyMap(),
    val log: List<String> = emptyList(),
)

class AutoflowViewModel : ViewModel() {

    private val engine = RuleEngine(seedRules())

    private val _ui = MutableStateFlow(
        AutoflowUiState(rules = engine.all, state = mapOf("battery" to "80", "mode" to "day")),
    )
    val ui: StateFlow<AutoflowUiState> = _ui.asStateFlow()

    fun toggle(id: RuleId) {
        val current = engine.all.first { it.id == id }
        engine.setEnabled(id, !current.enabled)
        _ui.value = _ui.value.copy(rules = engine.all)
    }

    fun fire(event: Event) {
        val result = engine.dispatch(event, _ui.value.state)
        val firedLines = result.firedRuleIds.map { id ->
            "fired: " + engine.all.first { it.id == id }.name
        }
        val actionLines = result.performed.map { "  -> " + it.label }
        _ui.value = _ui.value.copy(
            state = result.newState,
            log = (_ui.value.log + firedLines + actionLines).takeLast(50),
        )
    }

    private fun seedRules(): List<Rule> = listOf(
        Rule(
            id = RuleId("low_battery"),
            name = "Low battery saver",
            trigger = EventTypeTrigger("battery_changed"),
            condition = Numeric("battery", Numeric.Op.LE, 20.0),
            actions = listOf(SetState("mode", "saver"), Notify("Battery low: enabling saver")),
        ),
        Rule(
            id = RuleId("night_mode"),
            name = "Night mode",
            trigger = PayloadEqualsTrigger("phase", "night"),
            actions = listOf(SetState("mode", "night")),
        ),
    )
}

@Composable
fun AutoflowScreen(vm: AutoflowViewModel = viewModel()) {
    val state by vm.ui.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Autoflow", style = MaterialTheme.typography.headlineSmall)
        Text("state: " + state.state.entries.joinToString { "${it.key}=${it.value}" })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.fire(Event("battery_changed", mapOf("battery" to "15"))) }) {
                Text("Battery 15%")
            }
            Button(onClick = { vm.fire(Event("phase_changed", mapOf("phase" to "night"))) }) {
                Text("Go night")
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.rules, key = { it.id.raw }) { rule ->
                ElevatedCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium)
                            Text("${rule.actions.size} action(s)", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { vm.toggle(rule.id) })
                    }
                }
            }
        }
        if (state.log.isNotEmpty()) {
            Text("log", style = MaterialTheme.typography.labelMedium)
            state.log.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
