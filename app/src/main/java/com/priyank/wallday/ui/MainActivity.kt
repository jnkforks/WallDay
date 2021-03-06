package com.priyank.wallday.ui

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.observe
import com.priyank.wallday.R
import com.priyank.wallday.adapter.ImageWeekAdapter
import com.priyank.wallday.base.Status
import com.priyank.wallday.custom.*
import com.priyank.wallday.database.ImageWeek
import com.priyank.wallday.databinding.ActivityMainBinding
import com.priyank.wallday.receiver.WallPaperChangeBroadcastReceiver
import com.priyank.wallday.utils.Constants
import com.priyank.wallday.utils.Utils
import com.priyank.wallday.viewmodel.ImageWeekViewModel
import timber.log.Timber
import java.util.*


class MainActivity : AppCompatActivity(), ImageWeekAdapter.ImageWeekClickListener {

    private lateinit var binding: ActivityMainBinding
    private val imageWeekViewModel by viewModels<ImageWeekViewModel>()

    private lateinit var imageWeekAdapter: ImageWeekAdapter

    private var imageWeekList = mutableListOf<ImageWeek>()
    private var calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        val wallPaperChangingTime =
            getPreferenceValue(Constants.PREF_WALL_PAPER_CHANGING_TIME, "12:00 AM")
        changeWallpaperTimeText(wallPaperChangingTime)

        val hour = wallPaperChangingTime.substring(0..1)
        val minute = wallPaperChangingTime.substring(3..4)
        val amPM = wallPaperChangingTime.takeLast(2)
        calendar[Calendar.HOUR] = if (hour.toInt() == 12) 0 else hour.toInt()
        calendar[Calendar.MINUTE] = minute.toInt()
        calendar[Calendar.SECOND] = 0
        calendar[Calendar.MILLISECOND] = 0
        calendar[Calendar.AM_PM] = if (amPM == "AM") Calendar.AM else Calendar.PM

        binding.wallPaperChangingTime.setOnClickListener {
            showTimePicker()
        }
        changeTheAlarmManager()

        imageWeekAdapter = ImageWeekAdapter(this, imageWeekList)
        imageWeekAdapter.setImageWeekClickListener(this)
        binding.imageWeekViewPager.adapter = imageWeekAdapter

        with(binding.imageWeekViewPager) {
            clipToPadding = false
            clipChildren = false
            offscreenPageLimit = 3
        }

        val pageMarginPx = resources.getDimensionPixelOffset(R.dimen.pageMargin)
        val offsetPx = resources.getDimensionPixelOffset(R.dimen.offset)
        binding.imageWeekViewPager.setPageTransformer { page, position ->
            val offset = position * -(2 * offsetPx + pageMarginPx)
            page.translationX = offset
        }

        imageWeekViewModel.imagesWeek.observe(this) { response ->
            when (response.status) {
                Status.LOADING -> {
                    binding.progressCircular.visibility = View.VISIBLE
                }
                Status.ERROR -> {
                    binding.progressCircular.visibility = View.GONE
                    response.message?.let {
                        showToast(it)
                    }
                }
                else -> {
                    binding.progressCircular.visibility = View.GONE
                    imageWeekList.clear()
                    if (response.data.isNullOrEmpty()) {
                        val weekModelsForFirstTime = Utils.getWeekModelsForFirstTime()
                        imageWeekList.addAll(weekModelsForFirstTime)
                        imageWeekViewModel.addAllTheWeekImage(imageWeekList)
                    } else {
                        imageWeekList.addAll(response.data)
                    }
                    Timber.d(imageWeekList.toString())
                    imageWeekAdapter.notifyDataSetChanged()
                }
            }
        }

        imageWeekViewModel.updateImageOfDay.observe(this) { response ->
            when (response.status) {
                Status.LOADING -> {
//                    binding.progressCircular.visibility = View.VISIBLE
                }
                Status.ERROR -> {
//                    binding.progressCircular.visibility = View.GONE
                    response.message?.let {
                        showToast(it)
                    }
                }
                else -> {
//                    binding.progressCircular.visibility = View.GONE
                }
            }
        }

        imageWeekViewModel.insertAllTheWeekDataFirstTime.observe(this) { response ->
            when (response.status) {
                Status.LOADING -> {

                }
                Status.ERROR -> {
                    response.message?.let {
                        showToast(it)
                    }
                }
                else -> {

                }
            }
        }
    }

    private fun showTimePicker() {
        val timePickerDialog = TimePickerDialog(this, { _, hourOfDay, minute ->
            calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
            calendar.set(Calendar.MINUTE, minute)

            var selectedHour = calendar[Calendar.HOUR]
            val selectedMinute = calendar[Calendar.MINUTE]
            val selectedPeriod = calendar[Calendar.AM_PM]
            if (selectedHour == 0)
                selectedHour = 12

            val storeHour = String.format("%02d", selectedHour)
            val storeMinute = String.format("%02d", selectedMinute)
            val storeAMPM = if (selectedPeriod == Calendar.AM) "AM" else "PM"

            savePreferenceValue(
                Constants.PREF_WALL_PAPER_CHANGING_TIME,
                "$storeHour:$storeMinute $storeAMPM"
            )

            val wallPaperChangingTime = "$storeHour:$storeMinute $storeAMPM"
            changeWallpaperTimeText(wallPaperChangingTime)

            changeTheAlarmManager()
        }, calendar[Calendar.HOUR_OF_DAY], calendar[Calendar.MINUTE], false)
        timePickerDialog.show()
    }

    private fun changeWallpaperTimeText(wallPaperChangingTime: String) {
        val boldFont =
            ResourcesCompat.getFont(this, R.font.nunito_bold)
        val timeSpan = wallPaperChangingTime.createClickableSpan({
            it.cancelPendingInputEvents()
            showTimePicker()
        }, getColorResCompat(android.R.attr.textColorPrimary), boldFont)
        binding.wallPaperChangingTime.text = getString(R.string.wall_paper_changing_time)
        binding.wallPaperChangingTime.append(" ")
        binding.wallPaperChangingTime.append(timeSpan)
    }

    private fun changeTheAlarmManager() {

        //Checking if the selected calendar is in past or not.
        //If it is in past than we will make it to the future. so that alarm manager does not fire for previous time
        val currentTimeCalendar = Calendar.getInstance()
        val alarmTimeCalendar = Calendar.getInstance()
        alarmTimeCalendar.timeInMillis = calendar.timeInMillis
        if (calendar.timeInMillis < currentTimeCalendar.timeInMillis) {
            alarmTimeCalendar.add(Calendar.DATE, 1)
        }


        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = Intent(this, WallPaperChangeBroadcastReceiver::class.java)
        intent.action = Constants.INTENT_ACTION_WALL_PAPER_CHANGE
        val pendingIntent =
            PendingIntent.getBroadcast(this, 101, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        if (pendingIntent != null && alarmManager != null) {
            alarmManager.cancel(pendingIntent)
        }
        alarmManager?.setRepeating(
            AlarmManager.RTC_WAKEUP,
            alarmTimeCalendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    override fun onLongImageClick(item: ImageWeek, position: Int) {
        val imageWeek = imageWeekList[position]
        imageWeek.imagePath = ""
        imageWeek.isImageSelected = false
        imageWeek.authorName = ""
        imageWeek.authorURL = ""
        imageWeekAdapter.notifyItemChanged(position)
        imageWeekViewModel.updateImageInWeek(imageWeek)
    }

    override fun onSelectImageClick(item: ImageWeek, position: Int) {
        val intent = Intent(this, ImageListActivity::class.java)
        intent.putExtra(Constants.EXTRA_SELECTED_IMAGE_WEEK_POSITION, position)
        imageSelectResult.launch(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private val imageSelectResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    val isImageSelected =
                        data.getBooleanExtra(Constants.EXTRA_IS_IMAGE_SELECTED, false)
                    if (isImageSelected) {
                        val position =
                            data.getIntExtra(Constants.EXTRA_SELECTED_IMAGE_WEEK_POSITION, 0)
                        val path =
                            data.getStringExtra(Constants.EXTRA_SELECTED_IMAGE_PATH)
                        val author =
                            data.getStringExtra(Constants.EXTRA_SELECTED_IMAGE_AUTHOR)
                        val authorURL =
                            data.getStringExtra(Constants.EXTRA_SELECTED_IMAGE_AUTHOR_URL)

                        path?.let {
                            val imageWeek = imageWeekList[position]
                            imageWeek.imagePath = it
                            imageWeek.isImageSelected = true
                            imageWeek.authorName = author
                            imageWeek.authorURL = authorURL
                            imageWeekAdapter.notifyItemChanged(position)
                            imageWeekViewModel.updateImageInWeek(imageWeek)
                        }
                    }
                }
            }
        }
}