package com.aatorque.prefs

import android.app.Application
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val selectedFont = MutableLiveData<@receiver:FontRes Int>()
    val chartVisible = MutableLiveData<Boolean>()
    val minMaxBelow = MutableLiveData<Boolean>()
    val valueScale = MutableLiveData(1f)
    val labelScale = MutableLiveData(1f)

    val typefaceLiveData = selectedFont.map {
        return@map ResourcesCompat.getFont(getApplication(), it)!!
    }

    fun setFont(font: Int) {
        selectedFont.value = font
    }
}