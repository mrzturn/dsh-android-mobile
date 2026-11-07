package com.dsh.mobile.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.dsh.mobile.MainViewModel
import com.dsh.mobile.Route
import com.dsh.mobile.fold.rememberFoldPosture
import com.dsh.mobile.home.HomeScreen
import com.dsh.mobile.scanner.ScannerScreen
import com.dsh.mobile.session.SessionScreen
import com.dsh.mobile.ui.theme.DshTheme

/** Top-level composition: theme + route switch. */
@Composable
fun DshRoot(vm: MainViewModel) {
    DshTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val route by vm.route.collectAsState()
            val posture = rememberFoldPosture()
            when (val r = route) {
                Route.Home -> HomeScreen(vm)
                Route.Scanner -> ScannerScreen(vm, onBack = { vm.goHome() })
                is Route.Session -> SessionScreen(
                    connection = r.connection,
                    entryUrl = r.entryUrl,
                    posture = posture,
                    onBack = { vm.goHome() },
                    onRePair = { vm.openScanner() },
                )
            }
        }
    }
}
