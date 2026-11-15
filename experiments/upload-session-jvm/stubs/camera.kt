package de.kontrakurs.kontraprompter.camera
data class CameraCommand(val id:String,val type:String,val recordingId:String,val projectId:String?,val cameraFrom:Boolean?)
data class CameraAck(val id:String,val result:String)
data class CameraReport(val phase:String,val paused:Boolean?,val cameraFront:Boolean?,val cameraCanSwitch:Boolean,val recordingId:String?,val projectId:String?,val sizeBytes:Long?,val sha256:String?)
data class CameraPollRequest(val sequence:Long,val report:CameraReport,val acknowledgements:List<CameraAck>)
data class CameraPollResponse(val command:CameraCommand?,val remainingMs:Long)
class CameraLateReportConflictException(id:String):java.io.IOException()
fun matchesLateReportConflict(deviceId:String,request:CameraPollRequest,fields:Map<String,Any?>)=false
class CameraDeviceIdentity(val id:String,val publicKeyDer:ByteArray){fun signChallenge(id:String,nonce:String,action:String,digest:String)=ByteArray(0)}
