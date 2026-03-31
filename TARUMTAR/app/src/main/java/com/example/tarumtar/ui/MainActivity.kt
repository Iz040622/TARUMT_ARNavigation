package com.example.tarumtar.ui


import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.tarumtar.databinding.ActivityMainBinding
import com.example.tarumtar.Navigation
import com.example.tarumtar.navigation.routeSelectionActivity
import com.example.tarumtar.scanObject.ScanObject
import kotlin.jvm.java


class MainActivity : AppCompatActivity() {


    private lateinit var binding: ActivityMainBinding


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        setupTitle()
        setupClicks()
    }


    private fun setupTitle() {
// TAR (Red) UMT (Blue) AR (Black)
        val text = android.text.SpannableString("TAR UMT AR")


        text.setSpan(
            android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#E30613")),
            0, 3,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )


        text.setSpan(
            android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#1E40FF")),
            4, 7,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )


        text.setSpan(
            android.text.style.ForegroundColorSpan(android.graphics.Color.BLACK),
            8, 10,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )


        binding.txtTitle.text = text
    }


    private fun setupClicks() {
        binding.cardScan.setOnClickListener {
            val intent = Intent(this, ScanObject::class.java)
            startActivity(intent)
        }


        binding.cardNavigation.setOnClickListener {
            val intent = Intent(this, routeSelectionActivity::class.java)
            startActivity(intent)
        }


        binding.cardPet.setOnClickListener {
// TODO: My Pet
        }


        binding.cardShop.setOnClickListener {
// TODO: Shop
        }
    }
}