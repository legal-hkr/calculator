package sh.hnet.calculator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.hnet.calculator.about.WhatsNew
import sh.hnet.calculator.ui.CalculatorApp
import sh.hnet.calculator.ui.theme.CalculatorTheme
import sh.hnet.calculator.widget.CalculatorWidget

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) WhatsNew.recordStart(this)
        // Recreated by a change of the app's language: the widget's texts follow it (SPEC-widget §7.5)
        else CalculatorWidget.updateAll(this)
        setContent {
            CalculatorTheme { CalculatorApp() }
        }
    }
}
