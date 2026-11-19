package com.droidhost.launcher.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.launcher.R
import com.droidhost.launcher.runtime.FlatpakHost
import com.droidhost.launcher.vm.VmCatalog
import com.droidhost.launcher.vm.VmDistro
import com.droidhost.launcher.vm.VmDriverTier
import com.droidhost.launcher.vm.VmFamily
import com.droidhost.launcher.vm.VmImport
import com.droidhost.launcher.vm.VmMachine
import com.droidhost.launcher.vm.VmPreset
import com.droidhost.launcher.vm.VmProblem
import com.droidhost.launcher.vm.VmProbe
import com.droidhost.launcher.vm.VmStage
import com.droidhost.launcher.vm.VmStore
import com.droidhost.launcher.vm.VmUnpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The new-machine dialog: a distribution we package, or one the user brings.
 *
 * The imported route reads the archive the user picked, unpacks it into a machine of its own and
 * says what it found, so the driver route is known before the machine is used.
 */
@Composable
internal fun VmSourceDialog(
    onDismiss: () -> Unit,
    onStore: () -> Unit,
    onPick: (VmMachine, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    // One pick spawns one machine; a second tap before the dialog closes must not create another.
    var spawned by remember { mutableStateOf(false) }

    if (importing) {
        VmImportDialog(onDismiss = { importing = false }) { machine ->
            onPick(machine, false)
        }
        return
    }

    GlideAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vm_pick_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.vm_source_note), fontSize = 13.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp),
                )
                if (FlatpakHost.anyEligible(ctx)) {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Text(
                            stringResource(R.string.vm_source_apps), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Text(
                            stringResource(R.string.vm_source_apps_hint), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp),
                        )
                        FlatpakAppRow(flatpakHostSummary(ctx)) { onStore() }
                    }
                }
                Text(
                    stringResource(R.string.vm_source_preset), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    stringResource(R.string.vm_source_preset_hint), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp),
                )
                for (family in VmFamily.values()) {
                    val list = VmCatalog.distros.filter { it.family == family }
                    if (list.isEmpty()) continue
                    Text(
                        stringResource(family.labelRes).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    for (distro in list) {
                        val versions = VmCatalog.versionsFor(distro.id)
                        if (versions.isEmpty() || !distro.engine) {
                            VmDistroRow(distro) {}
                        } else {
                            VmDistroRow(distro, chevron = true) {
                                expanded = if (expanded == distro.id) null else distro.id
                            }
                            if (expanded == distro.id) {
                                Text(
                                    stringResource(R.string.vm_source_version_hint), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 4.dp),
                                )
                                for (version in versions) VmVersionRow(version) {
                                    if (spawned) return@VmVersionRow
                                    // Each machine keeps a rootfs of its own, downloaded for the
                                    // version picked, so several presets coexist.
                                    val created = VmStore.create(ctx, distro)
                                    if (created != null) {
                                        spawned = true
                                        VmPreset.start(ctx, created)
                                        onPick(created, false)
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.vm_source_import), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Text(
                    stringResource(R.string.vm_source_import_hint), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Button) { importing = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.vm_source_import_button), fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { PadTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The import route: pick the archive, unpack it, and report what the system turned out to be.
 *
 * The machine is only registered once the user confirms what was found; a system they do not
 * want is thrown away with its rootfs.
 */
@Composable
private fun VmImportDialog(
    onDismiss: () -> Unit,
    onImported: (VmMachine) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<VmStage?>(null) }
    var percent by remember { mutableStateOf(0) }
    var failure by remember { mutableStateOf<VmImport.Failure?>(null) }
    var failureProbe by remember { mutableStateOf<VmProbe?>(null) }
    var prepared by remember { mutableStateOf<VmImport.Staged?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(ctx, uri)
        val kind = VmUnpacker.Kind.of(name)
        failure = null
        failureProbe = null
        if (kind == null) {
            failure = VmImport.Failure.NOT_AN_ARCHIVE
            return@rememberLauncherForActivityResult
        }
        busy = true
        stage = VmStage.COPYING
        percent = 0
        scope.launch {
            val archive = withContext(Dispatchers.IO) { VmImport.copyIn(ctx, uri, name) }
            if (archive == null) {
                busy = false
                stage = null
                failure = VmImport.Failure.UNREADABLE
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                VmImport.stage(ctx, archive, kind) { step, value ->
                    scope.launch { stage = step; percent = value }
                }
            }
            // The rootfs holds the system now, so the copy has served its purpose.
            withContext(Dispatchers.IO) { archive.delete() }
            busy = false
            stage = null
            when (result) {
                is VmImport.Result.Prepared -> prepared = result.staged
                is VmImport.Result.Failed -> {
                    failure = result.reason
                    failureProbe = result.probe
                }
                is VmImport.Result.Ready -> onImported(result.machine)
            }
        }
    }

    // A system the user did not confirm is thrown away with its rootfs.
    fun close() {
        prepared?.let { VmImport.discard(it) }
        prepared = null
        onDismiss()
    }

    GlideAlertDialog(
        onDismissRequest = {
            if (busy) return@GlideAlertDialog
            close()
        },
        title = { Text(stringResource(R.string.vm_import_title)) },
        text = {
            Column {
                when {
                    busy -> {
                        Text(stageText(stage), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "$percent%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    failure != null -> Text(
                        failureText(failure!!, failureProbe), fontSize = 13.sp, lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    prepared != null -> VmProbePanel(prepared!!.probe)
                    else -> {
                        Text(
                            stringResource(R.string.vm_import_hint), fontSize = 13.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.vm_import_kernel), fontSize = 12.sp, lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            PadTextButton(onClick = {
                if (busy) return@PadTextButton
                val staged = prepared
                if (staged != null) {
                    // The user confirmed what the probe found: the machine becomes part of the app.
                    when (val result = VmImport.register(ctx, staged)) {
                        is VmImport.Result.Ready -> {
                            prepared = null
                            onImported(result.machine)
                        }
                        is VmImport.Result.Failed -> {
                            prepared = null
                            failure = result.reason
                            failureProbe = result.probe
                        }
                        is VmImport.Result.Prepared -> Unit
                    }
                } else {
                    picker.launch(arrayOf("*/*"))
                }
            }) { Text(stringResource(if (prepared != null) R.string.vm_import_confirm else R.string.vm_import_choose)) }
        },
        dismissButton = if (prepared == null && !busy) {
            { PadTextButton(onClick = { close() }) { Text(stringResource(R.string.common_cancel)) } }
        } else null,
    )
}

/** What the system turned out to be, and the driver route it will take. */
@Composable
private fun VmProbePanel(probe: VmProbe) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(probe.prettyName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        Text(
            "${probe.architecture} · " + (probe.packageManager?.let { "apt/dnf/apk: $it" } ?: "no package manager"),
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
        )
        val packaged = probe.driverTier == VmDriverTier.PACKAGED
        Text(
            if (packaged) stringResource(R.string.vm_probe_packaged) else stringResource(R.string.vm_probe_configuration),
            fontSize = 12.5.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
        )
        if (!packaged) {
            Text(
                stringResource(R.string.vm_probe_configuration_note), fontSize = 12.sp, lineHeight = 17.sp,
                color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** What the import is doing, in the user's language. */
@Composable
private fun stageText(stage: VmStage?): String = when (stage) {
    VmStage.COPYING -> stringResource(R.string.vm_stage_copying)
    VmStage.UNPACKING -> stringResource(R.string.vm_stage_unpacking)
    VmStage.PREPARING -> stringResource(R.string.vm_stage_preparing)
    VmStage.READY -> stringResource(R.string.vm_stage_ready)
    null -> ""
}

/** Why the import did not end in a machine, in the user's language. */
@Composable
private fun failureText(failure: VmImport.Failure, probe: VmProbe?): String = when (failure) {
    VmImport.Failure.UNREADABLE -> stringResource(R.string.vm_import_unreadable)
    VmImport.Failure.NOT_AN_ARCHIVE -> stringResource(R.string.vm_import_not_an_archive)
    VmImport.Failure.UNPACK_FAILED -> stringResource(R.string.vm_import_unpack_failed)
    VmImport.Failure.MACHINE_EXISTS -> stringResource(R.string.vm_import_machine_exists)
    VmImport.Failure.NOT_USABLE -> when (probe?.problem) {
        VmProblem.NOT_A_SYSTEM -> stringResource(R.string.vm_probe_not_a_system)
        VmProblem.NO_DISTRIBUTION -> stringResource(R.string.vm_probe_no_distribution)
        VmProblem.WRONG_ARCHITECTURE -> stringResource(
            R.string.vm_probe_wrong_arch, probe?.architecture.orEmpty(),
        )
        VmProblem.NOT_READABLE -> stringResource(R.string.vm_probe_not_readable)
        null -> stringResource(R.string.vm_import_not_usable)
    }
}

/** The name the file provider gives the document, without asking for a permission we do not need. */
private fun displayName(ctx: android.content.Context, uri: Uri): String {
    return runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
}

/** The built-in-apps entry: opens the app store against the selected flatpak host. */
@Composable
private fun FlatpakAppRow(summary: String, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceVariant)
            .clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.flathub_name),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface,
            )
            Text(summary, fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        Text("›", fontSize = 20.sp, color = colors.onSurfaceVariant)
    }
}

/** A distribution row: a built-in one drills into its versions, the rest stay gated. */
@Composable
private fun VmDistroRow(distro: VmDistro, chevron: Boolean = false, onPick: (VmDistro) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp).clip(shape)
            .background(colors.surfaceVariant)
            .clickable(enabled = distro.engine, role = Role.Button) { onPick(distro) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            distro.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
            modifier = Modifier.weight(1f).alpha(if (distro.engine) 1f else 0.45f),
        )
        if (chevron) Text("›", fontSize = 18.sp, color = colors.onSurfaceVariant)
        else if (distro.engine) Chip(stringResource(R.string.vm_install), ok = true)
        else Chip(stringResource(R.string.vm_needs_engine), ok = false)
    }
}

/** A version of a built-in distribution, the second level of the picker. */
@Composable
private fun VmVersionRow(version: VmCatalog.Builtin, onPick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, bottom = 3.dp).clip(shape)
            .background(colors.surfaceVariant)
            .clickable(role = Role.Button) { onPick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            version.version, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
            modifier = Modifier.weight(1f),
        )
        Chip(stringResource(R.string.vm_install), ok = true)
    }
}
