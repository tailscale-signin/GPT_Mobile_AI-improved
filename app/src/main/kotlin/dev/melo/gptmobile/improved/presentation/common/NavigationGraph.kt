package dev.melo.gptmobile.improved.presentation.common

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import dev.melo.gptmobile.improved.data.model.PlatformType
import dev.melo.gptmobile.improved.presentation.chat.chat.ChatRoomScreen
import dev.melo.gptmobile.improved.presentation.chat.chatlist.ChatListScreen
import dev.melo.gptmobile.improved.presentation.setting.AboutScreen
import dev.melo.gptmobile.improved.presentation.setting.AddPlatformScreen
import dev.melo.gptmobile.improved.presentation.setting.LicenseScreen
import dev.melo.gptmobile.improved.presentation.setting.PlatformSettingScreen
import dev.melo.gptmobile.improved.presentation.setting.SettingScreen
import dev.melo.gptmobile.improved.presentation.setting.localmodel.LocalModelScreen
import dev.melo.gptmobile.improved.presentation.setting.tools.EditToolConnectionScreen
import dev.melo.gptmobile.improved.presentation.setting.tools.ToolConnectionsScreen
import dev.melo.gptmobile.improved.presentation.setup.CompleteScreen
import dev.melo.gptmobile.improved.presentation.setup.DoneScreen
import dev.melo.gptmobile.improved.presentation.setup.MigratingScreen
import dev.melo.gptmobile.improved.presentation.setup.PlatformWizardScreen
import dev.melo.gptmobile.improved.presentation.setup.ReadyScreen
import dev.melo.gptmobile.improved.presentation.setup.SelectPlatformScreen
import dev.melo.gptmobile.improved.presentation.setup.SelectPlatformTypeScreen

@Composable
fun NavigationGraph(
    navController: NavHostController,
    startDestination: String,
    onFinishActivity: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None }
    ) {
        composable(Route.GET_STARTED) {
            ReadyScreen(
                onNavigateToPlatformList = {
                    navController.navigate(Route.SETUP_PLATFORM_LIST)
                }
            )
        }

        composable(Route.SETUP_PLATFORM_LIST) {
            SelectPlatformScreen(
                onNavigateToPlatformType = {
                    navController.navigate(Route.SETUP_PLATFORM_TYPE)
                },
                onComplete = {
                    navController.navigate(Route.SETUP_COMPLETE)
                }
            )
        }

        composable(Route.SETUP_PLATFORM_TYPE) {
            SelectPlatformTypeScreen(
                onPlatformSelected = { platformType ->
                    navController.navigate("${Route.SETUP_PLATFORM_WIZARD}/${platformType.name}")
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("${Route.SETUP_PLATFORM_WIZARD}/{platformType}") { backStackEntry ->
            val platformTypeName = backStackEntry.arguments?.getString("platformType")
            val platformType = platformTypeName?.let { PlatformType.valueOf(it) } ?: PlatformType.OPENAI
            PlatformWizardScreen(
                platformType = platformType,
                onComplete = {
                    navController.navigate(Route.SETUP_PLATFORM_LIST) {
                        popUpTo(Route.SETUP_PLATFORM_LIST) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Route.SETUP_COMPLETE) {
            DoneScreen(
                onFinish = {
                    navController.navigate(Route.CHAT_LIST) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Route.SETUP_LOCAL_MODELS) {
            CompleteScreen(
                onFinish = {
                    navController.navigate(Route.CHAT_LIST) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Route.MIGRATE_V2) {
            MigratingScreen(
                onMigrationComplete = {
                    navController.navigate(Route.CHAT_LIST) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Route.CHAT_LIST) {
            ChatListScreen(
                onNavigateToChat = { chatRoomId, enabledPlatforms ->
                    navController.navigate("chat_room/$chatRoomId?enabled=$enabledPlatforms")
                },
                onNavigateToSettings = {
                    navController.navigate(Route.SETTING_ROUTE)
                }
            )
        }

        composable(Route.CHAT_ROOM) {
            ChatRoomScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        navigation(
            startDestination = Route.SETTINGS,
            route = Route.SETTING_ROUTE
        ) {
            composable(Route.SETTINGS) {
                SettingScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToAddPlatform = {
                        navController.navigate(Route.ADD_PLATFORM)
                    },
                    onNavigateToPlatform = { platformUid ->
                        navController.navigate("platform_settings/$platformUid")
                    },
                    onNavigateToLocalModels = {
                        navController.navigate(Route.LOCAL_MODELS)
                    },
                    onNavigateToTools = {
                        navController.navigate(Route.TOOL_CONNECTIONS)
                    },
                    onNavigateToAbout = {
                        navController.navigate(Route.ABOUT_PAGE)
                    }
                )
            }

            composable(Route.ADD_PLATFORM) {
                AddPlatformScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onPlatformAdded = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Route.PLATFORM_SETTINGS) {
                PlatformSettingScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Route.LOCAL_MODELS) {
                LocalModelScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Route.TOOL_CONNECTIONS) {
                ToolConnectionsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToAdd = {
                        navController.navigate(Route.ADD_TOOL_CONNECTION)
                    },
                    onNavigateToEdit = { uid ->
                        navController.navigate("tool_connections/edit/$uid")
                    }
                )
            }

            composable(Route.ADD_TOOL_CONNECTION) {
                EditToolConnectionScreen(
                    connectionUid = null,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Route.EDIT_TOOL_CONNECTION) { backStackEntry ->
                val connectionUid = backStackEntry.arguments?.getString("connectionUid")
                EditToolConnectionScreen(
                    connectionUid = connectionUid,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Route.ABOUT_PAGE) {
                AboutScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToLicense = {
                        navController.navigate(Route.LICENSE)
                    }
                )
            }

            composable(Route.LICENSE) {
                LicenseScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
