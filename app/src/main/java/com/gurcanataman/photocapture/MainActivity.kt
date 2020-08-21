package com.gurcanataman.photocapture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.yalantis.ucrop.UCrop
import id.zelory.compressor.Compressor
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private var currentPhotoPath: String? = null
    private var loadingImageUri: Uri? = null

    private var isCamera = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnCamera.setOnClickListener {
            isCamera = true
            checkPermissions()
        }
        btnGallery.setOnClickListener {
            isCamera = false
            checkPermissions()
        }
    }

    // Öncelikle izinleri kontrol ediyoruz izinler verilmişse goIntent ile kamera veya galeriye gidiyoruz.
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when {
                //requireActivity().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), Constans.RequestCodeEntry.REQUEST_CODE_READ_EXTERNAL_STORAGE)
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> requestPermissions(
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), RC_WRITE_EXTERNAL_STORAGE
                )
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED -> requestPermissions(
                    arrayOf(
                        Manifest.permission.CAMERA
                    ), RC_CAMERA
                )
                else -> goIntent()
            }
        } else {
            goIntent()
        }

    }

    private fun goIntent() {
        if (isCamera) {
            startCameraIntent()
        } else {
            startGalleryIntent()
        }
    }

    private fun startGalleryIntent() {
        Intent(Intent.ACTION_GET_CONTENT).setType("image/*").also { takeGalleryIntent ->
            takeGalleryIntent.resolveActivity(packageManager)?.also {
                startActivityForResult(takeGalleryIntent, REQUEST_IMG_GALERY)
            } ?: Toast.makeText(
                this,
                "Galeriyi açmak için gerekli uygulamaya erişilemiyor",
                Toast.LENGTH_SHORT
            ).show()
        }

    }

    private fun startCameraIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(packageManager)?.also {
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) {
                    null
                }
                var photoUri: Uri? = null

                photoFile?.also {
                    photoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        FileProvider.getUriForFile(this, BuildConfig.FILES_AUTHORITY, it)
                    } else {
                        Uri.fromFile(it)
                    }
                    // val photoUri:Uri = FileProvider.getUriForFile(this, BuildConfig.FILES_AUTHORITY, it)
                    photoUri?.let { uri ->
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        startActivityForResult(takePictureIntent, REQUEST_IMG_CAMERA)
                    }
                }

            }
        }
    }

    private fun createImageFile(): File? {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "PNG_${timeStamp}_",
            ".png",
            storageDir
        ).apply {
            currentPhotoPath = absolutePath
        }
    }


    // Galeri ya da camerayı kullanmak için kullanıcının izin verme sonucunu kontrol ediyoruz:
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == RC_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkPermissions()
            }
        }

        if (requestCode == RC_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                checkPermissions()
            } else {
                Toast.makeText(this, "Lütfen kamera izni veriniz", Toast.LENGTH_SHORT).show()
            }
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMG_GALERY && resultCode == Activity.RESULT_OK) {
            // Caleriden Seçilen Resim:
            getImageDataFromGallery(data)
        } else if (requestCode == REQUEST_IMG_CAMERA && resultCode == Activity.RESULT_OK) {
            // Cameradan gelen resim
            getImageDataFromCamera()

        } else if (requestCode == UCrop.REQUEST_CROP && resultCode == Activity.RESULT_OK) {
            //Kırpılmış Resim:
            data?.let {
                val cropResult = UCrop.getOutput(it)
                cropResult?.let { result ->
                    loadingImageUri = result
                    result.path?.let { mPath ->
                        val file = File(mPath)
                        lifecycleScope.launch {
                            val compressedImageFile = Compressor.compress(this@MainActivity, file)
                            showFoto(compressedImageFile)
                        }

                    } ?: Log.e("Crop mPath", "null")
                }
            } ?: Toast.makeText(this, "Fotoğraf kırpma hatası", Toast.LENGTH_SHORT).show()

        }
    }


    private fun getImageDataFromGallery(data: Intent?) {
        val imageUri = data?.data
        imageUri?.let {
            startCrop(it)
        } ?: Toast.makeText(this, "Fotoğraf bilgisi alınırken bir hata oluştu!", Toast.LENGTH_SHORT)
            .show()


    }

    private fun getImageDataFromCamera() {
        currentPhotoPath?.let {
            val photoFile = File(it)
            startCrop(Uri.fromFile(photoFile))
        }

    }


    //UCrop başlatıyoruz:
    private fun startCrop(sourceUri: Uri) {
        //Dosya adı:
        val destinationFileName = "${getRandomString(18)}.png"
        val destinationUri = Uri.fromFile(File(cacheDir, destinationFileName))



        UCrop.of(sourceUri, destinationUri)
            .withOptions(getCropOptions())
            .start(this)
    }

    private fun getCropOptions(): UCrop.Options {
        val options = UCrop.Options()

        //UI
        options.setHideBottomControls(false)
        options.setFreeStyleCropEnabled(true)
        //options.setCircleDimmedLayer(true)

        //Colors:
        options.setStatusBarColor(ContextCompat.getColor(this, R.color.colorPrimary))
        options.setToolbarColor(ContextCompat.getColor(this, R.color.colorPrimary))
        options.setToolbarTitle("Fotoğraf Düzenleyici")

        return options
    }

    private fun clearImageCache() {
        currentPhotoPath?.let {
            val file = File(it)
            if (file.exists()) {
                //Fotoğraf yüklendikten sonra cacheten siliyoruz!
                file.delete()
            }
        }

        loadingImageUri?.let {
            it.path?.let { mPath ->
                val file = File(mPath)
                if (file.exists()) {
                    //Fotoğraf yüklendikten sonra cacheten siliyoruz!
                    file.delete()
                }

            }

        }
    }


    private fun showFoto(localFile: File) {
        Glide.with(this).load(localFile.absolutePath.toString()).into(imageView)
    }

    fun getRandomString(sizeOfRandomString: Int): String {
        val allowedChars = "0123456789qwertyuiopasdfghjklzxcvbnm"
        val random = Random()
        val sb = StringBuilder(sizeOfRandomString)
        for (i in 0 until sizeOfRandomString)
            sb.append(allowedChars[random.nextInt(allowedChars.length)])
        return sb.toString()
    }

    companion object {
        private const val REQUEST_IMG_CAMERA = 1
        private const val REQUEST_IMG_GALERY = 2

        private const val RC_WRITE_EXTERNAL_STORAGE = 101
        private const val RC_CAMERA = 102


    }


}