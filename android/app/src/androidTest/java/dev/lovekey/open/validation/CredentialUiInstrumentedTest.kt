package dev.lovekey.open.validation

import android.content.Intent
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lovekey.open.R
import dev.lovekey.open.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the actual settings view: setSingleLine must not undo key masking. */
@RunWith(AndroidJUnit4::class)
class CredentialUiInstrumentedTest {
    @Test fun actualKeyFieldMasksItsTextAndNeverSavesItToViewState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("screen", "models"))
        try {
            instrumentation.runOnMainSync {
                val key = activity.findViewById<EditText>(R.id.api_key)
                assertNotNull("Model settings must expose the key field", key)
                assertTrue("Key must use password transformation even after single-line setup", key.transformationMethod is PasswordTransformationMethod)
                assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, key.inputType and InputType.TYPE_MASK_VARIATION)
                assertFalse("Key must not enter Android view saved-state", key.isSaveEnabled)
                // No credential text or transformed text is included in test diagnostics.
                val masked = key.transformationMethod.getTransformation(key.text, key)
                assertTrue("Loaded credential characters must be hidden", masked.all { it == '\u2022' })
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
