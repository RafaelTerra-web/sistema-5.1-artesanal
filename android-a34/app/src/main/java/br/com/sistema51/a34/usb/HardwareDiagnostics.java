package br.com.sistema51.a34.usb;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit diagnostic run, off the UI thread, before selecting a live transport. */
public final class HardwareDiagnostics {
    private HardwareDiagnostics(){}
    public static JSONObject collect(Context context)throws Exception{
        UsbAudioController controller=new UsbAudioController(context);
        try{
            JSONObject report=new JSONObject().put("ok",true).put("kind","usb_hardware_snapshot")
                    .put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT).put("uid",android.os.Process.myUid())
                    .put("capturedAtMillis",System.currentTimeMillis()).put("usb",controller.snapshotJson());
            JSONArray controls=new JSONArray();
            for(UsbDevice device:controller.listDevices()){
                if(device.getVendorId()==0x0d8c&&device.getProductId()==0x0102){
                    if(controller.hasPermission(device.getDeviceId()))controls.put(Cm6206HidControl.readSnapshot(context,device.getDeviceId()));
                    else controls.put(new JSONObject().put("deviceId",device.getDeviceId()).put("status","usb_permission_required").put("readOnly",true));
                }
            }
            report.put("cm6206Controls",controls).put("scope","Descriptors, Android audio capabilities and read-only CM6206 registers. No capture, playback, register configuration or optical validation.");
            return report;
        }finally{controller.close();}
    }
}
