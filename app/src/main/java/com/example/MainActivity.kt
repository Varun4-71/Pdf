package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.engine.FileUtils
import com.example.engine.PdfEngine
import com.example.engine.ViewerLaunch
import com.example.model.PdfTool
import com.example.ui.screens.BatchScreen
import com.example.ui.screens.BlankPdfScreen
import com.example.ui.screens.CompressScreen
import com.example.ui.screens.CreateFromFilesScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ImagesToPdfScreen
import com.example.ui.screens.MergeScreen
import com.example.ui.screens.MetadataScreen
import com.example.ui.screens.MultiSearchScreen
import com.example.ui.screens.OrganizeScreen
import com.example.ui.screens.PageNumbersScreen
import com.example.ui.screens.PageOrganizerScreen
import com.example.ui.screens.PageToolsScreen
import com.example.ui.screens.PdfToImagesScreen
import com.example.ui.screens.ScanScreen
import com.example.ui.screens.SplitScreen
import com.example.ui.screens.TextPdfScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewer.ViewerScreen

class MainActivity : ComponentActivity() {

    /** A PDF another app asked us to open; consumed once by the navigation host. */
    private var pendingView by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize embedded PDFBox runtime for Android
        PdfEngine.initialize(this)
        FileUtils.pruneCache(this, 12L * 60L * 60L * 1000L)
        handleIncoming(intent)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF070B14)
                ) {
                    val navController = rememberNavController()

                    LaunchedEffect(pendingView) {
                        if (pendingView != null) {
                            navController.popBackStack("viewer", true)
                            navController.navigate("viewer")
                            pendingView = null
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Start,
                                animationSpec = tween(280)
                            ) + fadeIn(animationSpec = tween(280))
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Start,
                                animationSpec = tween(280)
                            ) + fadeOut(animationSpec = tween(280))
                        },
                        popEnterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = tween(280)
                            ) + fadeIn(animationSpec = tween(280))
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = tween(280)
                            ) + fadeOut(animationSpec = tween(280))
                        }
                    ) {
                        composable("home") {
                            HomeScreen(
                                onToolSelected = { tool ->
                                    navController.navigate(tool.id)
                                },
                                onNavigate = { route ->
                                    navController.navigate(route)
                                }
                            )
                        }

                        // Reading
                        composable("viewer") {
                            ViewerScreen(onBack = { navController.popBackStack() })
                        }
                        composable("search_pdfs") {
                            MultiSearchScreen(
                                onBack = { navController.popBackStack() },
                                onOpen = { navController.navigate("viewer") }
                            )
                        }

                        // Creating
                        composable("create_files") {
                            CreateFromFilesScreen(onBack = { navController.popBackStack() })
                        }
                        composable("scan") {
                            ScanScreen(onBack = { navController.popBackStack() })
                        }
                        composable("text_pdf") {
                            TextPdfScreen(onBack = { navController.popBackStack() })
                        }
                        composable("blank_pdf") {
                            BlankPdfScreen(onBack = { navController.popBackStack() })
                        }

                        // Page management and batch
                        composable("organizer") {
                            PageOrganizerScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            "page_tools/{tool}",
                            arguments = listOf(navArgument("tool") { type = NavType.StringType })
                        ) { entry ->
                            PageToolsScreen(
                                initialTool = entry.arguments?.getString("tool") ?: "crop",
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("batch") {
                            BatchScreen(onBack = { navController.popBackStack() })
                        }

                        // Original tools
                        composable(PdfTool.MERGE.id) {
                            MergeScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable(PdfTool.SPLIT.id) {
                            SplitScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable(PdfTool.COMPRESS.id) {
                            CompressScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable(PdfTool.EDIT_METADATA.id) {
                            MetadataScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable(PdfTool.IMAGES_TO_PDF.id) {
                            ImagesToPdfScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable(PdfTool.PDF_TO_IMAGES.id) {
                            PdfToImagesScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable(PdfTool.ORGANIZE_PAGES.id) {
                            OrganizeScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable(PdfTool.ADD_PAGE_NUMBERS.id) {
                            PageNumbersScreen(onNavigateBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(incoming: Intent?) {
        if (incoming != null && incoming.action == Intent.ACTION_VIEW) {
            val data = incoming.data
            if (data != null) {
                ViewerLaunch.open(data)
                pendingView = data
            }
        }
    }
}
