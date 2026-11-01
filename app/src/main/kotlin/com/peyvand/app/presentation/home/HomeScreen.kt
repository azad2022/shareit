package com.peyvand.app.presentation.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.text.BidiFormatter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peyvand.app.R
import com.peyvand.app.presentation.discovery.DeviceDiscoveryViewModel
import com.peyvand.app.presentation.network.NetworkEnvironmentViewModel
import com.peyvand.core.domain.discovery.DeviceDiscoveryFailure
import com.peyvand.core.domain.discovery.DiscoveryOperation
import com.peyvand.core.domain.discovery.DiscoveryStatus
import com.peyvand.core.domain.discovery.NearbyPeer
import com.peyvand.core.domain.discovery.WifiDirectAvailability
import com.peyvand.core.domain.discovery.WifiDirectPermissionPolicy
import com.peyvand.core.domain.network.VpnRouteStatus

@SuppressLint("InlinedApi")
@Composable
fun DeviceDiscoveryScreen(
    viewModel: DeviceDiscoveryViewModel,
    networkEnvironmentViewModel: NetworkEnvironmentViewModel,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val networkEnvironment by networkEnvironmentViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // Android 12 can grant approximate location only when both are requested together.
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    }
    val requiredPermission = permissionsToRequest.first()

    var permissionWasDenied by rememberSaveable { mutableStateOf(false) }
    var approximatePermissionOnly by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val requiredGranted = grants[requiredPermission] == true ||
            ContextCompat.checkSelfPermission(
                context,
                requiredPermission,
            ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            (grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED)

        approximatePermissionOnly = coarseGranted && !requiredGranted
        permissionWasDenied = !requiredGranted
        viewModel.refreshAvailability()
        if (requiredGranted) viewModel.startDiscovery()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAvailability()
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.stopDiscovery() }
    }

    val hasPermission = ContextCompat.checkSelfPermission(
        context,
        requiredPermission,
    ) == PackageManager.PERMISSION_GRANTED

    val shouldShowRationale =
        activity?.shouldShowRequestPermissionRationale(requiredPermission) == true
    val shouldOpenSettings = approximatePermissionOnly ||
        (permissionWasDenied && !shouldShowRationale)
    val isScanning = state.status == DiscoveryStatus.STARTING ||
        state.status == DiscoveryStatus.DISCOVERING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.home_brand_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (networkEnvironment.vpnRouteStatus == VpnRouteStatus.ACTIVE_FOR_APP) {
            VpnWarningCard(
                onOpenVpnSettings = { openVpnSettings(context) },
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadarScanner(isActive = state.status == DiscoveryStatus.DISCOVERING)

                Text(
                    text = when (state.status) {
                        DiscoveryStatus.IDLE -> stringResource(R.string.discovery_idle)
                        DiscoveryStatus.STARTING -> stringResource(R.string.discovery_starting)
                        DiscoveryStatus.DISCOVERING ->
                            stringResource(R.string.discovery_scanning)
                        DiscoveryStatus.FAILED -> stringResource(R.string.discovery_failed)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Text(
                    text = stringResource(R.string.discovery_permission_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Button(
                    enabled = isScanning ||
                        state.availability == WifiDirectAvailability.READY ||
                        state.availability == WifiDirectAvailability.PERMISSION_REQUIRED ||
                        state.availability == WifiDirectAvailability.UNAVAILABLE,
                    onClick = {
                        if (isScanning) {
                            viewModel.stopDiscovery()
                        } else if (hasPermission) {
                            permissionWasDenied = false
                            approximatePermissionOnly = false
                            viewModel.startDiscovery()
                        } else {
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Text(
                        text = if (isScanning) {
                            stringResource(R.string.discovery_stop)
                        } else {
                            stringResource(R.string.discovery_start)
                        },
                    )
                }
            }
        }

        AvailabilityCard(
            availability = state.availability,
            onOpenLocationSettings = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            },
            onOpenWifiSettings = {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            },
            onOpenAppSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    },
                )
            },
            permissionDenied = permissionWasDenied,
            approximatePermissionOnly = approximatePermissionOnly,
            shouldOpenSettings = shouldOpenSettings,
        )

        state.failure?.let { failure ->
            val failureText = when (failure) {
                DeviceDiscoveryFailure.PERMISSION_REQUIRED ->
                    stringResource(R.string.discovery_permission_required)
                DeviceDiscoveryFailure.BUSY ->
                    stringResource(R.string.discovery_error_busy)
                DeviceDiscoveryFailure.UNSUPPORTED ->
                    stringResource(R.string.discovery_error_unsupported)
                DeviceDiscoveryFailure.PLATFORM_ERROR ->
                    stringResource(R.string.discovery_error_generic)
            }
            Text(
                text = failureText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            state.failureOperation?.let { operation ->
                val operationLabel = when (operation) {
                    DiscoveryOperation.CLEAR_SERVICE_REQUESTS ->
                        stringResource(R.string.discovery_stage_clear_requests)
                    DiscoveryOperation.CLEAR_LOCAL_SERVICES ->
                        stringResource(R.string.discovery_stage_clear_services)
                    DiscoveryOperation.ADD_LOCAL_SERVICE ->
                        stringResource(R.string.discovery_stage_add_local_service)
                    DiscoveryOperation.ADD_SERVICE_REQUEST ->
                        stringResource(R.string.discovery_stage_add_request)
                    DiscoveryOperation.DISCOVER_SERVICES ->
                        stringResource(R.string.discovery_stage_start_scan)
                }
                Text(
                    text = stringResource(R.string.discovery_error_operation, operationLabel),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (state.peers.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = pluralStringResource(
                        R.plurals.discovery_peers_found_count,
                        state.peers.size,
                        state.peers.size,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                state.peers.forEach { peer ->
                    PeerCard(peer = peer)
                }
            }
        } else if (state.status == DiscoveryStatus.DISCOVERING) {
            Text(
                text = stringResource(R.string.discovery_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = stringResource(R.string.discovery_security_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.discovery_security_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun VpnWarningCard(
    onOpenVpnSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = stringResource(R.string.vpn_route_warning_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.vpn_route_warning_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenVpnSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.vpn_open_settings))
            }
        }
    }
}

private fun openVpnSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun AvailabilityCard(
    availability: WifiDirectAvailability,
    onOpenLocationSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    permissionDenied: Boolean,
    approximatePermissionOnly: Boolean,
    shouldOpenSettings: Boolean,
) {
    val message = when (availability) {
        WifiDirectAvailability.CHECKING ->
            stringResource(R.string.discovery_availability_checking)
        WifiDirectAvailability.READY ->
            stringResource(R.string.discovery_availability_ready)
        WifiDirectAvailability.PERMISSION_REQUIRED ->
            stringResource(R.string.discovery_availability_permission)
        WifiDirectAvailability.LOCATION_SERVICES_DISABLED ->
            stringResource(R.string.discovery_availability_location)
        WifiDirectAvailability.WIFI_P2P_DISABLED ->
            stringResource(R.string.discovery_availability_wifi_disabled)
        WifiDirectAvailability.UNSUPPORTED ->
            stringResource(R.string.discovery_availability_unsupported)
        WifiDirectAvailability.UNAVAILABLE ->
            stringResource(R.string.discovery_availability_unavailable)
    }

    if (availability == WifiDirectAvailability.READY ||
        availability == WifiDirectAvailability.CHECKING
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            when (availability) {
                WifiDirectAvailability.PERMISSION_REQUIRED -> {
                    if (approximatePermissionOnly) {
                        Text(
                            text = stringResource(
                                R.string.discovery_permission_precision_required,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (permissionDenied) {
                        Text(
                            text = stringResource(R.string.discovery_permission_denied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (shouldOpenSettings) {
                        OutlinedButton(
                            onClick = onOpenAppSettings,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.discovery_open_app_settings))
                        }
                    }
                }

                WifiDirectAvailability.LOCATION_SERVICES_DISABLED -> {
                    OutlinedButton(
                        onClick = onOpenLocationSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.discovery_open_location_settings))
                    }
                }

                WifiDirectAvailability.WIFI_P2P_DISABLED -> {
                    OutlinedButton(
                        onClick = onOpenWifiSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.discovery_open_wifi_settings))
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: NearbyPeer,
) {
    val rawName = peer.displayName
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.discovery_unknown_device)
    val safeName = BidiFormatter.getInstance().unicodeWrap(rawName)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "P",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = safeName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.discovery_peer_compatible),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RadarScanner(
    isActive: Boolean,
) {
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }

    if (isActive && animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "peyvand-discovery-radar")
        val pulse by transition.animateFloat(
            initialValue = 0.18f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_650, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "peyvand-discovery-pulse",
        )
        RadarDrawingCanvas(
            isActive = true,
            pulse = pulse,
        )
    } else {
        // No infinite transition is composed while idle or when Android disables motion.
        RadarDrawingCanvas(
            isActive = isActive,
            pulse = 0.18f,
        )
    }
}

@Composable
private fun RadarDrawingCanvas(
    isActive: Boolean,
    pulse: Float,
) {
    val description = stringResource(
        if (isActive) R.string.discovery_radar_active
        else R.string.discovery_radar_idle,
    )
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = Modifier
            .size(164.dp)
            .semantics { contentDescription = description },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension * 0.42f
        val strokeWidth = 1.5.dp.toPx()

        drawCircle(
            color = outline.copy(alpha = 0.55f),
            radius = outerRadius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        drawCircle(
            color = outline.copy(alpha = 0.45f),
            radius = outerRadius * 0.68f,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        drawCircle(
            color = outline.copy(alpha = 0.35f),
            radius = outerRadius * 0.34f,
            center = center,
            style = Stroke(width = strokeWidth),
        )

        if (isActive) {
            drawCircle(
                color = primary.copy(alpha = 0.20f),
                radius = outerRadius * pulse,
                center = center,
                style = Stroke(width = strokeWidth * 1.4f),
            )
            drawCircle(
                color = primary.copy(alpha = 0.08f),
                radius = outerRadius * pulse,
                center = center,
            )
        }

        drawCircle(
            color = primary,
            radius = outerRadius * 0.095f,
            center = center,
        )
    }
}
