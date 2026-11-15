package de.kontrakurs.kontraprompter.video
import java.io.File
data class SavedRecording(val id:String,val bytes:Long,val sha256:String,val file:File,val partial:Boolean=false,val origin:RecordingOrigin=RecordingOrigin.MANUAL,val projectId:String?=null)
