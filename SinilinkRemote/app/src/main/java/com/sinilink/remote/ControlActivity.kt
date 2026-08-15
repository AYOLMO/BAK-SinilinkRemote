package com.sinilink.remote

import android.os.Bundle
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class ControlActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        findViewById<View>(R.id.btn_aux).setOnClickListener {
            sendSource(Protocol.CMD_SOURCE_AUX, R.string.switched_aux)
        }
        findViewById<View>(R.id.btn_bt).setOnClickListener {
            sendSource(Protocol.CMD_SOURCE_BT, R.string.switched_bt)
        }
    }

    private fun sendSource(source: Int, messageRes: Int) {
        if (SinilinkBle.sendSource(source)) {
            Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.connection_lost, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SinilinkBle.disconnect()
    }
}
