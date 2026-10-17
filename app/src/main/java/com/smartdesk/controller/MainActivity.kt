package com.smartdesk.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.material3.Button
import androidx.compose.material3.Text

private val SdBackground = Color(0xFF0D1212)
private val SdSurface = Color(0xFF151C1C)
private val SdSurface2 = Color(0xFF1B2423)
private val SdSurface3 = Color(0xFF202B2A)

private val SdAccent = Color(0xFF6FA7A3)
private val SdAccentDark = Color(0xFF3F7775)
private val SdAccentSoft = Color(0xFF284544)

private val SdText = Color(0xFFE8F0EF)
private val SdMuted = Color(0xFF91A3A1)
private val SdBorder = Color(0xFF30403F)
private val SdDim = Color(0xB8000000)

private val SdRadiusLarge = 30.dp
private val SdRadiusMedium = 22.dp
private val SdRadiusSmall = 17.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SmartDeskTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SdBackground
                ) {
                    SmartDeskApp()
                }
            }
        }
    }
}

@Composable
fun SmartDeskTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = SdAccent,
            onPrimary = SdBackground,
            background = SdBackground,
            onBackground = SdText,
            surface = SdSurface,
            onSurface = SdText,
            surfaceVariant = SdSurface2,
            onSurfaceVariant = SdMuted,
            outline = SdBorder
        ),
        content = content
    )
}

@Composable
fun SmartDeskApp() {

    var screen by remember {
        mutableStateOf("connection")
    }

    var host by remember {
        mutableStateOf("")
    }

    var port by remember {
        mutableStateOf("8765")
    }

    var pairingCode by remember {
        mutableStateOf("")
    }

    var status by remember {
        mutableStateOf("Disconnected")
    }

    var serverMessage by remember {
        mutableStateOf("")
    }

    val connection = remember {

        SmartDeskConnection(

            onStatusChanged = { newStatus ->

                status = newStatus

                when (newStatus) {

                    "Pairing required" -> {
                        screen = "pairing"
                    }

                    "Connected" -> {
                        screen = "connected"
                    }

                    "Session expired" -> {
                        screen = "connection"
                    }
                }
            },

            onServerMessage = { message ->

                serverMessage =
                    message.toString()

                val responseStatus =
                    message.optString("status")

                if (responseStatus == "ok") {

                    val data =
                        message.optJSONObject("data")

                    val state =
                        data?.optString("state")

                    if (state == "ACTIVE") {

                        status = "Connected"
                        screen = "connected"
                    }
                }
            },

            onPingResult = { }
        )
    }

    when (screen) {

        "connection" -> {

            ConnectionScreen(

                host = host,
                port = port,
                status = status,
                serverMessage = serverMessage,

                onHostChanged = {
                    host = it
                },

                onPortChanged = {
                    port = it
                },

                onConnect = {

                    val cleanHost =
                        host.trim()

                    if (cleanHost.isEmpty()) {

                        status =
                            "Enter a host IP"

                        return@ConnectionScreen
                    }

                    val portNumber =
                        port.toIntOrNull()

                    if (
                        portNumber == null ||
                        portNumber !in 1..65535
                    ) {

                        status =
                            "Invalid port"

                        return@ConnectionScreen
                    }

                    serverMessage = ""

                    connection.connect(
                        cleanHost,
                        portNumber
                    )
                }
            )
        }

        "pairing" -> {

            PairingScreen(

                pairingCode = pairingCode,
                status = status,

                onPairingCodeChanged = {
                    pairingCode = it
                },

                onPair = {
                    connection.pair(pairingCode)
                }
            )
        }

        "connected" -> {

            ConnectedScreen(

                onMouseMove = { dx, dy ->
                    connection.sendMouseMove(
                        dx,
                        dy
                    )
                },

                onLeftClick = {
                    connection.leftClick()
                },

                onRightClick = {
                    connection.rightClick()
                },

                onDoubleClick = {
                    connection.doubleClick()
                },

                onScroll = { dx, dy ->
                    connection.scroll(
                        dx,
                        dy
                    )
                },

                onPlayPause = {
                    connection.playPause()
                },

                onMute = {
                    connection.mute()
                },

                onTypeText = { text ->
                    connection.typeText(text)
                },

                onBackspace = {
                    connection.backspace()
                },
		onOpenGestureControl = {
    		screen = "gesture"
		},

                onDisconnect = {

                    connection.disconnect()

                    screen = "connection"
                    status = "Disconnected"
                    serverMessage = ""
                    pairingCode = ""
                }
            )
        }
	"gesture" -> {
    		GestureScreen(
                connection = connection,
        		onBack = {
            	screen = "connected"
        }
    )
}
    }
}

@Composable
fun ConnectionScreen(
    host: String,
    port: String,
    status: String,
    serverMessage: String,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnect: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SdBackground)
            .padding(28.dp),

        verticalArrangement =
            Arrangement.Center
    ) {

        Text(
            text = "SMARTDESK",
            color = SdAccent,
            fontSize = 13.sp,
            letterSpacing = 3.sp
        )

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        Text(
            text = "Connect",
            color = SdText,
            fontSize = 34.sp
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Text(
            text = "Connect to your computer",
            color = SdMuted,
            fontSize = 15.sp
        )

        Spacer(
            modifier = Modifier.height(34.dp)
        )

        SdTextField(
            value = host,
            onValueChange = onHostChanged,
            label = "Host IP",
            placeholder = "192.168.1.42",
            keyboardType = KeyboardType.Uri
        )

        Spacer(
            modifier = Modifier.height(14.dp)
        )

        SdTextField(
            value = port,
            onValueChange = onPortChanged,
            label = "Port",
            placeholder = "8765",
            keyboardType = KeyboardType.Number
        )

        Spacer(
            modifier = Modifier.height(22.dp)
        )

        SdPrimaryButton(
            text = "Connect",
            onClick = onConnect
        )

        Spacer(
            modifier = Modifier.height(18.dp)
        )

        Text(
            text = status,
            color = SdMuted,
            fontSize = 13.sp
        )

        if (serverMessage.isNotEmpty()) {

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text = serverMessage,
                color = SdMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun PairingScreen(
    pairingCode: String,
    status: String,
    onPairingCodeChanged: (String) -> Unit,
    onPair: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SdBackground)
            .padding(28.dp),

        verticalArrangement =
            Arrangement.Center
    ) {

        Text(
            text = "SMARTDESK",
            color = SdAccent,
            fontSize = 13.sp,
            letterSpacing = 3.sp
        )

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        Text(
            text = "Pair device",
            color = SdText,
            fontSize = 34.sp
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Text(
            text =
                "Enter the 6-digit code shown on your computer.",
            color = SdMuted,
            fontSize = 15.sp
        )

        Spacer(
            modifier = Modifier.height(28.dp)
        )

        SdTextField(
            value = pairingCode,

            onValueChange = {

                if (
                    it.length <= 6 &&
                    it.all { character ->
                        character.isDigit()
                    }
                ) {
                    onPairingCodeChanged(it)
                }
            },

            label = "Pairing code",
            placeholder = "123456",
            keyboardType = KeyboardType.Number
        )

        Spacer(
            modifier = Modifier.height(18.dp)
        )

        SdPrimaryButton(
            text = "Pair",
            enabled = pairingCode.length == 6,
            onClick = onPair
        )

        Spacer(
            modifier = Modifier.height(18.dp)
        )

        Text(
            text = status,
            color = SdMuted,
            fontSize = 13.sp
        )
    }
}

@Composable
fun ConnectedScreen(
    onMouseMove: (Int, Int) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onScroll: (Int, Int) -> Unit,
    onPlayPause: () -> Unit,
    onMute: () -> Unit,
    onTypeText: (String) -> Unit,
    onBackspace: () -> Unit,
    onOpenGestureControl: () -> Unit,
    onDisconnect: () -> Unit
) {

    var keyboardOpen by remember {
        mutableStateOf(false)
    }

    var keyboardText by remember {
        mutableStateOf("")
    }

    val view =
        LocalView.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SdBackground)
            .padding(
                top = 14.dp,
                bottom = 14.dp,
                start = 14.dp,
                end = 14.dp
            )
    ) {

        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            TopControls(
                onPlayPause = {

                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP
                    )

                    onPlayPause()
                },

                onMute = {

                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP
                    )

                    onMute()
                },

                onDisconnect = {

                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.LONG_PRESS
                    )

                    onDisconnect()
                }
            )
		Spacer(
    modifier = Modifier.height(8.dp)
)

Button(
    onClick = onOpenGestureControl,
    modifier = Modifier
        .fillMaxWidth()
        .height(40.dp)
) {
    Text("Gesture Control")
}

Spacer(
    modifier = Modifier.height(8.dp)
)

            Spacer(
                modifier = Modifier.height(10.dp)
            )

            Touchpad(
                onMouseMove = onMouseMove,

                onLeftClick = {

                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP
                    )

                    onLeftClick()
                },

                onRightClick = {

                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP
                    )

                    onRightClick()
                },

                onDoubleClick = {

                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP
                    )

                    onDoubleClick()
                },

                onScroll = onScroll,

                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Spacer(
                modifier = Modifier.height(10.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),

                horizontalArrangement =
                    Arrangement.spacedBy(9.dp),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                ClickControl(
                    label = "LEFT",

                    modifier =
                        Modifier.weight(0.8f),

                    onClick = {

                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP
                        )

                        onLeftClick()
                    }
                )

                KeyboardPill(
                    modifier =
                        Modifier.weight(2.2f),

                    onClick = {

                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP
                        )

                        keyboardOpen = true
                    }
                )

                ClickControl(
                    label = "RIGHT",

                    modifier =
                        Modifier.weight(0.8f),

                    onClick = {

                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP
                        )

                        onRightClick()
                    }
                )
            }
        }

        AnimatedVisibility(

            visible = keyboardOpen,

            enter =
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = 180
                        )
                ),

            exit =
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = 160
                        )
                ),

            modifier =
                Modifier.fillMaxSize()
        ) {

            Box(
                modifier =
                    Modifier.fillMaxSize()
            ) {

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SdDim)
                        .clickable {

                            keyboardOpen = false
                        }
                )

                KeyboardPanel(

                    text = keyboardText,

                    onTextChanged = { newText ->

                        val oldText =
                            keyboardText

                        if (
                            newText.length >
                            oldText.length &&
                            newText.startsWith(
                                oldText
                            )
                        ) {

                            val added =
                                newText.substring(
                                    oldText.length
                                )

                            onTypeText(added)

                        } else if (
                            newText.length <
                            oldText.length &&
                            oldText.startsWith(
                                newText
                            )
                        ) {

                            val removed =
                                oldText.length -
                                        newText.length

                            repeat(removed) {
                                onBackspace()
                            }
                        }

                        keyboardText =
                            newText
                    },

                    onBackspace = {

                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP
                        )

                        onBackspace()
                    },

                    onEnter = {

                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP
                        )

                        onTypeText("\n")
                    },

                    onSpace = {

                        onTypeText(" ")
                    },

                    onClose = {

                        keyboardOpen = false
                    }
                )
            }
        }
    }
}

@Composable
fun TopControls(
    onPlayPause: () -> Unit,
    onMute: () -> Unit,
    onDisconnect: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),

        horizontalArrangement =
            Arrangement.spacedBy(9.dp),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        TopIconButton(
            symbol = "▶",
            onClick = onPlayPause
        )

        TopIconButton(
            symbol = "⌁",
            onClick = onMute
        )

        Spacer(
            modifier = Modifier.weight(1f)
        )

        TopIconButton(
            symbol = "×",
            onClick = onDisconnect
        )
    }
}

@Composable
fun TopIconButton(
    symbol: String,
    onClick: () -> Unit
) {

    var pressed by remember {
        mutableStateOf(false)
    }

    val scale by animateFloatAsState(

        targetValue =
            if (pressed) {
                0.91f
            } else {
                1f
            },

        animationSpec =
            tween(
                durationMillis = 100,
                easing =
                    FastOutSlowInEasing
            ),

        label = "topButtonScale"
    )

    Box(
        modifier = Modifier
            .size(46.dp)
            .scale(scale)
            .clip(
                RoundedCornerShape(
                    SdRadiusSmall
                )
            )
            .background(SdSurface)
            .border(
                width = 1.dp,
                color = SdBorder,
                shape =
                    RoundedCornerShape(
                        SdRadiusSmall
                    )
            )
            .clickable {

                pressed = true
                onClick()
                pressed = false
            },

        contentAlignment =
            Alignment.Center
    ) {

        Text(
            text = symbol,
            color = SdText,
            fontSize = 18.sp
        )
    }
}

@Composable
fun ClickControl(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {

    var pressed by remember {
        mutableStateOf(false)
    }

    val scale by animateFloatAsState(

        targetValue =
            if (pressed) {
                0.95f
            } else {
                1f
            },

        animationSpec =
            tween(
                durationMillis = 100
            ),

        label = "clickScale"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .scale(scale)
            .clip(
                RoundedCornerShape(
                    SdRadiusMedium
                )
            )
            .background(SdSurface)
            .border(
                width = 1.dp,
                color = SdBorder,
                shape =
                    RoundedCornerShape(
                        SdRadiusMedium
                    )
            )
            .clickable {

                pressed = true
                onClick()
                pressed = false
            },

        contentAlignment =
            Alignment.Center
    ) {

        Text(
            text = label,
            color = SdMuted,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
fun KeyboardPill(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {

    Box(
        modifier = modifier
            .height(58.dp)
            .clip(
                RoundedCornerShape(
                    21.dp
                )
            )
            .background(SdSurface2)
            .border(
                width = 1.dp,
                color = SdBorder,
                shape =
                    RoundedCornerShape(
                        21.dp
                    )
            )
            .clickable(
                onClick = onClick
            ),

        contentAlignment =
            Alignment.Center
    ) {

        Row(
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(
                text = "⌨",
                color = SdAccent,
                fontSize = 18.sp
            )

            Text(
                text = "Keyboard",
                color = SdText,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun KeyboardPanel(
    text: String,
    onTextChanged: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSpace: () -> Unit,
    onClose: () -> Unit
) {

    Box(
        modifier = Modifier.fillMaxSize(),

        contentAlignment =
            Alignment.BottomCenter
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    bottom = 8.dp
                )
                .clip(
                    RoundedCornerShape(
                        28.dp
                    )
                )
                .background(SdSurface3)
                .border(
                    width = 1.dp,
                    color = SdBorder,
                    shape =
                        RoundedCornerShape(
                            28.dp
                        )
                )
                .padding(14.dp)
        ) {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text = "Keyboard",
                    color = SdText,
                    fontSize = 14.sp
                )

                Spacer(
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "×",
                    color = SdMuted,
                    fontSize = 24.sp,

                    modifier =
                        Modifier.clickable {
                            onClose()
                        }
                )
            }

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            KeyboardInput(
                value = text,
                onValueChange =
                    onTextChanged
            )

            Spacer(
                modifier = Modifier.height(10.dp)
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                KeyboardActionButton(
                    text = "Backspace",
                    modifier =
                        Modifier.weight(1f),
                    onClick =
                        onBackspace
                )

                KeyboardActionButton(
                    text = "Enter",
                    modifier =
                        Modifier.weight(1f),
                    onClick =
                        onEnter
                )

                KeyboardActionButton(
                    text = "Space",
                    modifier =
                        Modifier.weight(1f),
                    onClick =
                        onSpace
                )
            }
        }
    }
}

@Composable
fun KeyboardInput(
    value: String,
    onValueChange: (String) -> Unit
) {

    OutlinedTextField(
        value = value,

        onValueChange =
            onValueChange,

        modifier =
            Modifier.fillMaxWidth(),

        placeholder = {

            Text(
                text = "Type here...",
                color = SdMuted
            )
        },

        singleLine = true,

        textStyle =
            TextStyle(
                color = SdText,
                fontSize = 16.sp
            ),

        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    KeyboardType.Text
            ),

        shape =
            RoundedCornerShape(
                18.dp
            ),

        colors =
            OutlinedTextFieldDefaults.colors(

                focusedContainerColor =
                    SdSurface,

                unfocusedContainerColor =
                    SdSurface,

                focusedBorderColor =
                    SdAccent,

                unfocusedBorderColor =
                    SdBorder,

                cursorColor =
                    SdAccent,

                focusedTextColor =
                    SdText,

                unfocusedTextColor =
                    SdText
            )
    )
}

@Composable
fun KeyboardActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(
                RoundedCornerShape(
                    16.dp
                )
            )
            .background(SdSurface)
            .border(
                width = 1.dp,
                color = SdBorder,
                shape =
                    RoundedCornerShape(
                        16.dp
                    )
            )
            .clickable {
                onClick()
            },

        contentAlignment =
            Alignment.Center
    ) {

        Text(
            text = text,
            color = SdMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
fun SdTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType
) {

    OutlinedTextField(

        value = value,

        onValueChange =
            onValueChange,

        modifier =
            Modifier.fillMaxWidth(),

        label = {

            Text(
                text = label,
                color = SdMuted
            )
        },

        placeholder = {

            Text(
                text = placeholder,
                color = SdMuted
            )
        },

        singleLine = true,

        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    keyboardType
            ),

        shape =
            RoundedCornerShape(
                SdRadiusMedium
            ),

        colors =
            OutlinedTextFieldDefaults.colors(

                focusedContainerColor =
                    SdSurface,

                unfocusedContainerColor =
                    SdSurface,

                focusedBorderColor =
                    SdAccent,

                unfocusedBorderColor =
                    SdBorder,

                focusedTextColor =
                    SdText,

                unfocusedTextColor =
                    SdText,

                cursorColor =
                    SdAccent
            )
    )
}

@Composable
fun SdPrimaryButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {

    Button(

        onClick = onClick,

        enabled = enabled,

        modifier =
            Modifier
                .fillMaxWidth()
                .height(54.dp),

        shape =
            RoundedCornerShape(
                SdRadiusMedium
            ),

        colors =
            ButtonDefaults.buttonColors(

                containerColor =
                    SdAccentDark,

                contentColor =
                    SdText,

                disabledContainerColor =
                    SdSurface2,

                disabledContentColor =
                    SdMuted
            )
    ) {

        Text(
            text = text,
            fontSize = 14.sp
        )
    }
}

@Composable
fun Touchpad(
    onMouseMove: (Int, Int) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onScroll: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {

    val density =
        LocalDensity.current

    val tapSlop =
        with(density) {
            18.dp.toPx()
        }

    val shape =
        RoundedCornerShape(
            SdRadiusLarge
        )

    Box(

        modifier = modifier
            .clip(shape)
            .background(SdSurface2)
            .border(
                width = 1.dp,
                color = SdBorder,
                shape = shape
            )
            .pointerInput(Unit) {

                var activeIds =
                    emptySet<PointerId>()

                var maxFingers = 0

                var lastCentroid =
                    Offset.Zero

                var totalDistance = 0f

                var moved = false

                var fractionalX = 0f
                var fractionalY = 0f

                var scrollAccumX = 0f
                var scrollAccumY = 0f

                var lastTapTime = 0L
                var waitingForDoubleTap = false

                while (true) {

                    val event =
                        awaitPointerEventScope {
                            awaitPointerEvent()
                        }

                    val changes =
                        event.changes

                    val pressed =
                        changes.filter {
                            it.pressed
                        }

                    val currentCount =
                        pressed.size

                    if (
                        activeIds.isEmpty() &&
                        currentCount > 0
                    ) {

                        activeIds =
                            pressed
                                .map {
                                    it.id
                                }
                                .toSet()

                        maxFingers =
                            currentCount

                        lastCentroid =
                            centroid(pressed)

                        totalDistance =
                            0f

                        moved =
                            false

                        fractionalX =
                            0f

                        fractionalY =
                            0f

                        scrollAccumX =
                            0f

                        scrollAccumY =
                            0f
                    }

                    if (
                        activeIds.isNotEmpty() &&
                        currentCount > 0
                    ) {

                        if (
                            currentCount >
                            maxFingers
                        ) {

                            maxFingers =
                                currentCount
                        }

                        val currentCentroid =
                            centroid(pressed)

                        val delta =
                            currentCentroid -
                                    lastCentroid

                        totalDistance +=
                            delta.getDistance()

                        if (
                            totalDistance >
                            tapSlop
                        ) {

                            moved =
                                true
                        }

                        if (
                            maxFingers == 1 &&
                            currentCount == 1
                        ) {

                            fractionalX +=
                                delta.x * 2.0f

                            fractionalY +=
                                delta.y * 2.0f

                            val moveX =
                                fractionalX
                                    .roundToInt()

                            val moveY =
                                fractionalY
                                    .roundToInt()

                            fractionalX -=
                                moveX

                            fractionalY -=
                                moveY

                            if (
                                moveX != 0 ||
                                moveY != 0
                            ) {

                                onMouseMove(
                                    moveX,
                                    moveY
                                )
                            }
                        }

                        if (
                            maxFingers == 2 &&
                            currentCount == 2
                        ) {

                            scrollAccumX +=
                                delta.x

                            scrollAccumY +=
                                delta.y

                            val scrollX =
                                (
                                        -scrollAccumX /
                                                4f
                                        ).roundToInt()

                            val scrollY =
                                (
                                        -scrollAccumY /
                                                4f
                                        ).roundToInt()

                            if (
                                scrollX != 0
                            ) {

                                onScroll(
                                    scrollX,
                                    0
                                )

                                scrollAccumX -=
                                    scrollX * -4f
                            }

                            if (
                                scrollY != 0
                            ) {

                                onScroll(
                                    0,
                                    scrollY
                                )

                                scrollAccumY -=
                                    scrollY * -4f
                            }
                        }

                        lastCentroid =
                            currentCentroid

                        for (change in changes) {
                            change.consume()
                        }
                    }

                    if (
                        activeIds.isNotEmpty() &&
                        currentCount == 0
                    ) {

                        val now =
                            System.currentTimeMillis()

                        if (!moved) {

                            if (
                                maxFingers == 1
                            ) {

                                if (
                                    waitingForDoubleTap &&
                                    now -
                                    lastTapTime <=
                                    350L
                                ) {

                                    onDoubleClick()

                                    waitingForDoubleTap =
                                        false

                                    lastTapTime =
                                        0L

                                } else {

                                    waitingForDoubleTap =
                                        true

                                    lastTapTime =
                                        now
                                }

                            } else if (
                                maxFingers == 2
                            ) {

                                onRightClick()
                            }
                        }

                        activeIds =
                            emptySet()

                        maxFingers =
                            0

                        lastCentroid =
                            Offset.Zero

                        totalDistance =
                            0f

                        moved =
                            false

                        fractionalX =
                            0f

                        fractionalY =
                            0f

                        scrollAccumX =
                            0f

                        scrollAccumY =
                            0f
                    }

                    if (
                        waitingForDoubleTap &&
                        System.currentTimeMillis() -
                        lastTapTime > 350L
                    ) {

                        onLeftClick()

                        waitingForDoubleTap =
                            false

                        lastTapTime =
                            0L
                    }
                }
            }
    )
}

private fun centroid(
    changes: List<PointerInputChange>
): Offset {

    if (changes.isEmpty()) {
        return Offset.Zero
    }

    var x = 0f
    var y = 0f

    for (change in changes) {

        x += change.position.x
        y += change.position.y
    }

    return Offset(
        x / changes.size,
        y / changes.size
    )
}