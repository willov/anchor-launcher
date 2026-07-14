package app.lawnchair.ui.preferences.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.preferences.preferenceManager
import com.android.launcher3.InvariantDeviceProfile

@Composable
fun ColumnScope.GridOverridesPreview(
    modifier: Modifier = Modifier,
    populateGrid: Boolean = false,
    // Extra invalidation key: the IDP is rebuilt whenever this changes. The wizard passes its
    // icon-size / labels / density state here so the preview re-renders live as the user adjusts
    // them — those values feed the IDP build via prefs but are not Compose state, so without this
    // the preview only refreshed on a full recompose (e.g. screen lock/unlock).
    previewKey: Any? = Unit,
    updateGridOptions: DeviceProfileOverrides.DBGridInfo.() -> DeviceProfileOverrides.DBGridInfo,
) {
    WithWallpaper { wallpaper ->
        DummyLauncherBox(
            modifier = modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .clip(MaterialTheme.shapes.large),
        ) {
            WallpaperPreview(
                wallpaper = wallpaper,
                modifier = Modifier.fillMaxSize(),
            )
            DummyLauncherLayout(
                idp = createPreviewIdp(previewKey, updateGridOptions),
                modifier = Modifier.fillMaxSize(),
                populateGrid = populateGrid,
            )
        }
    }
}

@Composable
fun createPreviewIdp(
    previewKey: Any? = Unit,
    updateGridOptions: DeviceProfileOverrides.DBGridInfo.() -> DeviceProfileOverrides.DBGridInfo,
): InvariantDeviceProfile {
    val context = LocalContext.current
    val prefs = preferenceManager()

    // Keyed on previewKey AND the resolved grid dims so any change (cols/rows from the lambda, or
    // icon-size/labels passed via previewKey) rebuilds the IDP. derivedStateOf alone did not fire
    // because the inputs are read from prefs (blocking), not from observable Compose state.
    val resolved = updateGridOptions(DeviceProfileOverrides.DBGridInfo(prefs))
    val newIdp = remember(previewKey, resolved) {
        InvariantDeviceProfile(context, resolved)
    }
    return newIdp
}
