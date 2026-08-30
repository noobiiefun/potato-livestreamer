package com.potato.livestreamer

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.potato.livestreamer.R
import com.potato.livestreamer.irl.IrlMainActivity
import com.potato.livestreamer.relay.RelayMainActivity

class ModeSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_selection)

        findViewById<Button>(R.id.btnModePC).setOnClickListener {
            startActivity(Intent(this, RelayMainActivity::class.java))
        }

        findViewById<Button>(R.id.btnModeIRL).setOnClickListener {
            startActivity(Intent(this, IrlMainActivity::class.java))
        }
    }
}
