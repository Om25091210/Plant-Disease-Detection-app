package com.om.agrohealth

import android.Manifest
import android.app.PendingIntent.getActivity
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.FileDescriptor
import java.io.FileInputStream
import java.lang.Math.abs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


class MainActivity : AppCompatActivity() {

    val REQUEST_CODE_STORAGE_PERMISSION = 1
    private val CAMERA_REQUEST = 1888
    private val MY_CAMERA_PERMISSION_CODE = 100
    var selected_uri_pdf = Uri.parse("")
    val PICK_FILE = 1
    // Our model expects a RGB image, hence the channel size is 3
    private val channelSize = 3
    // Width of the image that our model expects
    var inputImageWidth = 224
    // Height of the image that our model expects
    var inputImageHeight = 224

    // Size of the input buffer size
    private var modelInputSize = inputImageWidth * inputImageHeight * channelSize

    // Output you get from your model
    val resultArray = Array(1) { ByteArray(4) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val window: Window = this.getWindow()
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor =
            ContextCompat.getColor(this, R.color.white)
        window.navigationBarColor =
            ContextCompat.getColor(this, R.color.white)

        //val openAI = OpenAI("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")

        findViewById<TextView>(R.id.textView2).setOnClickListener{
            //Ask for permission
            if (ContextCompat.checkSelfPermission(
                    getApplicationContext(),
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQUEST_CODE_STORAGE_PERMISSION
                )
            } else {
                select_file()
            }
        }

        findViewById<TextView>(R.id.pic).setOnClickListener{
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), MY_CAMERA_PERMISSION_CODE)
            } else {
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                startActivityForResult(cameraIntent, CAMERA_REQUEST)
            }
        }

    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MY_CAMERA_PERMISSION_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "camera permission granted", Toast.LENGTH_LONG).show()
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                startActivityForResult(cameraIntent, CAMERA_REQUEST)
            } else {
                Toast.makeText(this, "camera permission denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    val interpreter by lazy {
        loadModelFile()?.let { Interpreter(it) }
    }
    private fun loadModelFile(): MappedByteBuffer? {
        val fileDescriptor: AssetFileDescriptor = assets.openFd("model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val startOffset: Long = fileDescriptor.startOffset
        val declaredLength: Long = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {  //bytebuffer best fits for byte array
        // Specify the size of the byteBuffer
        val byteBuffer = ByteBuffer.allocateDirect(modelInputSize)
        byteBuffer.order(ByteOrder.nativeOrder())
        // Calculate the number of pixels in the image
        val pixels = IntArray(inputImageWidth * inputImageHeight)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        // Loop through all the pixels and save them into the buffer
        for (i in 0 until inputImageWidth) {
            for (j in 0 until inputImageHeight) {
                val pixelVal = pixels[pixel++]
                // Do note that the method to add pixels to byteBuffer is different for quantized models over normal tflite models
                byteBuffer.put((pixelVal shr 16 and 0xFF).toByte())
                byteBuffer.put((pixelVal shr 8 and 0xFF).toByte())
                byteBuffer.put((pixelVal and 0xFF).toByte())
            }
        }

        // Recycle the bitmap to save memory
        bitmap.recycle()
        return byteBuffer
    }
    private fun select_file() {
        val intent = Intent()
        intent.type = "image/*"
        intent.action = Intent.ACTION_GET_CONTENT
        startActivityForResult(
            Intent.createChooser(intent, "Select File"),
            PICK_FILE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE && resultCode == RESULT_OK) {
            if (data != null) {
                selected_uri_pdf = data.data
                show_file_upload(selected_uri_pdf)
            }
        }
        if (requestCode === CAMERA_REQUEST && resultCode === RESULT_OK) {
            selected_uri_pdf = data?.data
            val thumbnail = data?.extras?.get("data")
            camera_image(thumbnail as Bitmap)
        }
    }

    private fun camera_image(bitmap: Bitmap) {
        // Read the bitmap from a local file
        val bitmap = bitmap
        // Resize the bitmap so that it's 224x224
        val resizedImage =
            bitmap?.let { Bitmap.createScaledBitmap(it, inputImageWidth, inputImageHeight, true) }

        // Convert the bitmap to a ByteBuffer
        val modelInput = resizedImage?.let { convertBitmapToByteBuffer(it) }
        // Perform inference on the model
        interpreter?.run(modelInput, resultArray)

        // Class 1
        Log.e(TAG, "Corn (Maize) - Gray leaf : ${abs(resultArray[0][0].toInt())}")
        // Class 2
        Log.e(TAG, "Corn (Maize) - Common rust : ${abs(resultArray[0][1].toInt())}")
        // Class 3
        Log.e(TAG, "Healthy Corn Leaf : ${abs(resultArray[0][2].toInt())}")
        // Class 4
        Log.e(TAG, "Corn (Maize) - Northen Leaf Blight : ${abs(resultArray[0][3].toInt())}")
        printMax(abs(resultArray[0][0].toInt()), abs(resultArray[0][1].toInt()), abs(resultArray[0][2].toInt()),abs(resultArray[0][3].toInt()))  // Output: "3 is the maximum"
    }

    private fun show_file_upload(strTxt: Uri) {
        // Read the bitmap from a local file
        val bitmap = getBitmapFromUri(strTxt)
        // Resize the bitmap so that it's 224x224
        val resizedImage =
            bitmap?.let { Bitmap.createScaledBitmap(it, inputImageWidth, inputImageHeight, true) }

        // Convert the bitmap to a ByteBuffer
        val modelInput = resizedImage?.let { convertBitmapToByteBuffer(it) }
        // Perform inference on the model
        interpreter?.run(modelInput, resultArray)

        // Class 1
        Log.e(TAG, "Tomato Healthy : ${abs(resultArray[0][0].toInt())}")
        // Class 2
        Log.e(TAG, "Yellow leave curl Virus : ${abs(resultArray[0][1].toInt())}")
        // Class 3
        Log.e(TAG, "Target Spot : ${abs(resultArray[0][2].toInt())}")
        // Class 4
        Log.e(TAG, "Spider mites : ${abs(resultArray[0][3].toInt())}")
        printMax(abs(resultArray[0][0].toInt()), abs(resultArray[0][1].toInt()), abs(resultArray[0][2].toInt()),abs(resultArray[0][3].toInt()))  // Output: "3 is the maximum"
    }
    fun printMax(a: Int, b: Int, c: Int, d:Int) {
        val max = Math.max(Math.max(a, b), Math.max(c, d));
        if (max == a) {
            findViewById<TextView>(R.id.prediction).text="Tomato Healthy"
            println("$a is the maximum")
        } else if (max == b) {
            findViewById<TextView>(R.id.prediction).text="Yellow leave curl Virus"
            println("$b is the maximum")
        }
        else if(max==c){
            findViewById<TextView>(R.id.prediction).text="Target Spot"
        }
        else {
            findViewById<TextView>(R.id.prediction).text="Spider mites"
            println("$c is the maximum")
        }
    }

    fun getBitmapFromUri(uri: Uri?): Bitmap? {
        val parcelFileDescriptor = contentResolver.openFileDescriptor(uri!!, "r")
        val fileDescriptor: FileDescriptor = parcelFileDescriptor!!.fileDescriptor
        val image = BitmapFactory.decodeFileDescriptor(fileDescriptor)
        parcelFileDescriptor.close()
        return image
    }
}