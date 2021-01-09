package com.bwl.toastdemo

import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    var flag = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.btn_show_toast).setOnClickListener {
            ToastManagerCompat.show(Toast.makeText(this@MainActivity, getHelloText(), Toast.LENGTH_SHORT))
            Handler().postDelayed( {
                ToastManagerCompat.show(Toast.makeText(this@MainActivity, getHelloText(), Toast.LENGTH_SHORT))
                ToastManagerCompat.show(Toast.makeText(this@MainActivity, getHelloText(), Toast.LENGTH_SHORT))
                ToastManagerCompat.show(Toast.makeText(this@MainActivity, getHelloText(), Toast.LENGTH_SHORT))
                ToastManagerCompat.show(Toast.makeText(this@MainActivity, getHelloText(), Toast.LENGTH_SHORT))
            }, 1000)
        }

        findViewById<View>(R.id.btn_show_dialog).setOnClickListener {
            AlertDialog.Builder(this@MainActivity).setTitle("title").setMessage("message\nmessage\nmessage").show()
        }
    }

    private fun getHelloText() = "Hello world: ${flag++}"
}