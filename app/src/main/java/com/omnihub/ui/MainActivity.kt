package com.omnihub.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.omnihub.data.UserPrefs
import com.omnihub.ui.screens.*
import com.omnihub.ui.theme.OmniHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OmniHubTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    var setupDone by remember {
                        mutableStateOf(UserPrefs.isSetupComplete(context) && UserPrefs.hasAcceptedLegal(context))
                    }
                    val navController = rememberNavController()
                    if (!setupDone) {
                        NavHost(navController = navController, startDestination = "onboarding") {
                            composable("onboarding") {
                                OnboardingScreen(
                                    onComplete = { setupDone = true },
                                    onOpenLegal = { doc -> navController.navigate("legal/${doc.name}") }
                                )
                            }
                            composable("legal/{doc}", arguments = listOf(navArgument("doc") { type = NavType.StringType })) { entry ->
                                val doc = LegalDoc.valueOf(entry.arguments?.getString("doc") ?: LegalDoc.TERMS.name)
                                LegalScreen(doc = doc, onBack = { navController.popBackStack() })
                            }
                        }
                    } else {
                        OmniHubNav()
                    }
                }
            }
        }
    }
}

@Composable
fun OmniHubNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(
                onOpenSettings = { navController.navigate("settings") },
                onOpenCustomize = { navController.navigate("customize") },
                onOpenSkills = { navController.navigate("skills") },
                onOpenProjects = { navController.navigate("projects") },
                onOpenSources = { navController.navigate("sources") },
                onOpenStore = { navController.navigate("store") },
                onOpenConnectors = { navController.navigate("connectors") },
                onOpenTerminal = { navController.navigate("terminal") }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenConnectors = { navController.navigate("connectors") },
                onOpenLegal = { doc -> navController.navigate("legal/${doc.name}") },
                onOpenAnalytics = { navController.navigate("analytics") },
                onOpenStore = { navController.navigate("store") }
            )
        }
        composable("customize") { CustomizeScreen(onBack = { navController.popBackStack() }) }
        composable("connectors") { ConnectorsScreen(onBack = { navController.popBackStack() }) }
        composable("skills") { SkillsScreen(onBack = { navController.popBackStack() }) }
        composable("projects") { ProjectsScreen(onBack = { navController.popBackStack() }) }
        composable("sources") {
            SourcesScreen(
                onBack = { navController.popBackStack() },
                onOpenStore = { navController.navigate("store") }
            )
        }
        composable("analytics") { AnalyticsScreen(onBack = { navController.popBackStack() }) }
        composable("store") { StoreScreen(onBack = { navController.popBackStack() }) }
        composable("file_explorer") { FileExplorerScreen(onBack = { navController.popBackStack() }) }
        composable("terminal") { TerminalScreen(onBack = { navController.popBackStack() }) }
        composable("legal/{doc}", arguments = listOf(navArgument("doc") { type = NavType.StringType })) { entry ->
            val doc = LegalDoc.valueOf(entry.arguments?.getString("doc") ?: LegalDoc.PRIVACY.name)
            LegalScreen(doc = doc, onBack = { navController.popBackStack() })
        }
    }
}
