/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.about

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceDivider
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupHeading
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupItem
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import app.lawnchair.ui.preferences.navigation.AboutLicenses
import app.lawnchair.ui.preferences.navigation.AnchorDonate
import com.android.launcher3.BuildConfig
import com.android.launcher3.R

@Composable
fun About(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs: PreferenceManager = PreferenceManager.getInstance(context)

    PreferenceLayoutLazyColumn(
        label = stringResource(id = R.string.about_label),
        modifier = modifier,
        backArrowVisible = !LocalIsExpandedScreen.current,
    ) {
        item {
            Spacer(Modifier.padding(top = 8.dp))
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_home_comp),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape),
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
        item {
            Text(
                text = stringResource(id = R.string.derived_app_name),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (prefs.hideVersionInfo.get()) {
                        prefs.pseudonymVersion.get() + " (pseudonym)"
                    } else {
                        BuildConfig.VERSION_DISPLAY_NAME
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            val navController = LocalNavController.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                LawnchairLink(
                    iconResId = R.drawable.ic_github,
                    label = stringResource(id = R.string.github),
                    modifier = Modifier.weight(weight = 1f),
                    url = ANCHOR_GITHUB,
                )
                LawnchairLink(
                    iconResId = R.drawable.ic_donate,
                    label = stringResource(id = R.string.donate),
                    modifier = Modifier.weight(weight = 1f),
                    url = "",
                    onClick = { navController.navigate(AnchorDonate) },
                )
            }
        }
        item {
            PreferenceGroupHeading(
                stringResource(R.string.anchor_built_on_heading),
            )
        }
        item {
            PreferenceGroupItem(
                cutTop = false,
                cutBottom = true,
            ) {
                ClickablePreference(
                    label = "Lawnchair",
                    subtitle = stringResource(id = R.string.anchor_built_on_lawnchair_desc),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/LawnchairLauncher/lawnchair".toUri())
                        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                    },
                )
            }
        }
        item {
            Spacer(Modifier.height(3.dp))
        }
        item {
            PreferenceGroupItem(
                cutTop = true,
                cutBottom = false,
            ) {
                ClickablePreference(
                    label = "AOSP Launcher3",
                    subtitle = stringResource(id = R.string.anchor_built_on_aosp_desc),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://android.googlesource.com/platform/packages/apps/Launcher3".toUri())
                        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                    },
                )
            }
        }
        item {
            PreferenceGroupHeading(
                stringResource(R.string.legal),
            )
        }
        item {
            PreferenceGroupItem(
                cutTop = false,
                cutBottom = true,
            ) {
                NavigationActionPreference(
                    label = stringResource(id = R.string.acknowledgements),
                    destination = AboutLicenses,
                )
            }
        }
        item {
            Spacer(Modifier.height(3.dp))
        }
        item {
            PreferenceGroupItem(
                cutTop = true,
                cutBottom = false,
            ) {
                ClickablePreference(
                    label = stringResource(id = R.string.privacy_policy),
                    onClick = {
                        val webpage = PRIVACY_POLICY.toUri()
                        val intent = Intent(Intent.ACTION_VIEW, webpage)
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        }
                    },
                )
            }
        }
    }
}

private const val ANCHOR_GITHUB = "https://github.com/willov/anchor-launcher"
private const val PRIVACY_POLICY = "https://willov-apps.se/anchor/privacy.html"
