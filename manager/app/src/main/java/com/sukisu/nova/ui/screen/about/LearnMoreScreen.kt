package com.sukisu.nova.ui.screen.about

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import com.sukisu.nova.R
import com.sukisu.nova.ui.LocalUiMode
import com.sukisu.nova.ui.UiMode
import com.sukisu.nova.ui.navigation3.LocalNavigator
import com.sukisu.nova.ui.theme.Shapes
import com.sukisu.nova.ui.theme.ThemeController
import com.sukisu.nova.ui.theme.Typography
import com.sukisu.nova.ui.theme.animateAsState
import com.sukisu.nova.ui.theme.rememberKernelSUColorScheme

private val MiuixDefaultSeed = Color(0xFF3482FF)

/** 「注意」那一段：放大 + #E91E63 */
private val NoteColor = Color(0xFFE91E63)
private val NoteFontSize = 17.sp

/** 「关于SukiSU-Next」那一行：放大 + 纯黑 */
private val AboutTitleColor = Color(0xFF000000)
private val AboutTitleFontSize = 18.sp

// 只吃 URL 合法字符，避免把紧跟其后的中文（如"自行获取"）一起吞进链接
private val UrlRegex = Regex("""https?://[A-Za-z0-9\-._~:/?#@!$&'()*+,;=%]+""")
private val DoubledSchemeRegex = Regex("""(https?://)(https?://)+""")

private class LinkRange(val start: Int, val end: Int, val url: String)

private class BodyText(val annotated: AnnotatedString, val links: List<LinkRange>)

private fun readLearnMore(context: android.content.Context): String = runCatching {
    context.assets.open("learn_more.txt").bufferedReader().use { it.readText() }
}.getOrElse { "" }

/**
 * 正文渲染：http(s) 片段加下划线 + 主题色，点击范围严格限制在链接字符本身（不吞后面的中文）；
 * 顺便修掉重复的 "https://https://"，给「注意」段落上粉色放大、给「关于SukiSU-Next」行上纯黑放大。
 */
private fun buildBody(body: String, linkColor: Color): BodyText {
    val text = body
        .replace(DoubledSchemeRegex, "$1")
        .replace("**", "")

    val links = UrlRegex.findAll(text)
        .map { LinkRange(it.range.first, it.range.last + 1, it.value) }
        .toList()

    val annotated = buildAnnotatedString {
        append(text)
        links.forEach { link ->
            addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                link.start,
                link.end,
            )
        }
        val noteStart = text.indexOf("注意")
        if (noteStart >= 0) {
            addStyle(SpanStyle(color = NoteColor, fontSize = NoteFontSize), noteStart, text.length)
        }
        val aboutStart = text.indexOf("关于SukiSU-Next")
        if (aboutStart >= 0) {
            val lineEnd = text.indexOf('\n', aboutStart).let { if (it < 0) text.length else it }
            addStyle(
                SpanStyle(color = AboutTitleColor, fontSize = AboutTitleFontSize),
                aboutStart,
                lineEnd,
            )
        }
    }

    return BodyText(annotated, links)
}

@Composable
fun LearnMoreScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val body = remember { readLearnMore(context) }

    when (LocalUiMode.current) {
        UiMode.Material -> LearnMoreContent(body = body, onBack = dropUnlessResumed { navigator.pop() })
        UiMode.Miuix -> MiuixSkinMaterialTheme {
            LearnMoreContent(body = body, onBack = dropUnlessResumed { navigator.pop() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LearnMoreContent(body: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val uriHandler = LocalUriHandler.current
        val linkColor = MaterialTheme.colorScheme.primary
        val styled = remember(body, linkColor) { buildBody(body, linkColor) }
        val layout = remember { mutableStateOf<TextLayoutResult?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            BasicText(
                text = styled.annotated,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                onTextLayout = { layout.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(styled.links) {
                        detectTapGestures { position ->
                            val result = layout.value ?: return@detectTapGestures
                            val offset = result.getOffsetForPosition(position)
                            styled.links
                                .firstOrNull { offset >= it.start && offset < it.end }
                                ?.let { runCatching { uriHandler.openUri(it.url) } }
                        }
                    },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MiuixSkinMaterialTheme(content: @Composable () -> Unit) {
    val appSettings = remember { ThemeController.getAppSettings() }
    val colorMode = appSettings.colorMode
    val darkTheme = colorMode.isDark || (colorMode.isSystem && isSystemInDarkTheme())
    val seedColor = when {
        appSettings.keyColor != 0 -> Color(appSettings.keyColor)
        colorMode.isMonet -> Color.Unspecified
        else -> MiuixDefaultSeed
    }
    val colorScheme = rememberKernelSUColorScheme(
        seedColor = seedColor,
        isDark = darkTheme,
        isAmoled = colorMode.isAmoled,
        paletteStyle = appSettings.paletteStyle,
        colorSpec = appSettings.colorSpec,
    )

    MaterialExpressiveTheme(
        colorScheme = colorScheme.animateAsState(),
        motionScheme = MotionScheme.expressive(),
        shapes = Shapes,
        typography = Typography,
        content = content,
    )
}
