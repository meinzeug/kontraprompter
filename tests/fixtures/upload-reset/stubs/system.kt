package android.system
import java.io.FileDescriptor
object OsConstants {const val O_RDONLY=0;fun S_ISDIR(mode:Int)=true}
object Os {fun open(path:String,flags:Int,mode:Int)=FileDescriptor();fun fstat(fd:FileDescriptor)=Stat();fun fsync(fd:FileDescriptor){};fun close(fd:FileDescriptor){} }
class Stat {val st_mode=0}
