package be.mobel.ibeacon;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.Log;
import com.radiusnetworks.ibeacon.IBeaconManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by alexvanboxel on 17/06/14.
 */
public class Beacons implements BluetoothAdapter.LeScanCallback {

    private static String TAG = "Beacons";

    Map<String, IBeacon> beaconMap = new HashMap<>();

    public int getCount() {
        return beaconMap.size();
    }

    public static class IBeacon {
        public String proximityUuid;
        public int txPower;
        public int major;
        public int minor;
        public int rssi;
        public String bluetoothAddress;
        public String proximity;

        @Override
        public String toString() {
            return proximityUuid + " major:" + major + " minor:" + minor + " rssi:" + rssi + " > " + proximity;
        }
    }

    final private static char[] hexArray = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    public static IBeacon fromScanData(byte[] scanData, int rssi, BluetoothDevice device) {
        int startByte = 2;
        boolean patternFound = false;
        while (startByte <= 5) {
            if (((int) scanData[startByte + 2] & 0xff) == 0x02 &&
                    ((int) scanData[startByte + 3] & 0xff) == 0x15) {
                // yes!  This is an iBeacon
                patternFound = true;
                break;
            } else if (((int) scanData[startByte] & 0xff) == 0x2d &&
                    ((int) scanData[startByte + 1] & 0xff) == 0x24 &&
                    ((int) scanData[startByte + 2] & 0xff) == 0xbf &&
                    ((int) scanData[startByte + 3] & 0xff) == 0x16) {
                Log.d(TAG, "This is a proprietary Estimote beacon advertisement that does not meet the iBeacon standard.  Identifiers cannot be read.");
                IBeacon iBeacon = new IBeacon();
                iBeacon.major = 0;
                iBeacon.minor = 0;
                iBeacon.proximityUuid = "00000000-0000-0000-0000-000000000000";
                iBeacon.txPower = -55;
                return iBeacon;
            } else if (((int) scanData[startByte] & 0xff) == 0xad &&
                    ((int) scanData[startByte + 1] & 0xff) == 0x77 &&
                    ((int) scanData[startByte + 2] & 0xff) == 0x00 &&
                    ((int) scanData[startByte + 3] & 0xff) == 0xc6) {
                Log.d(TAG, "This is a proprietary Gimbal beacon advertisement that does not meet the iBeacon standard.  Identifiers cannot be read.");
                IBeacon iBeacon = new IBeacon();
                iBeacon.major = 0;
                iBeacon.minor = 0;
                iBeacon.proximityUuid = "00000000-0000-0000-0000-000000000000";
                iBeacon.txPower = -55;
                return iBeacon;
            }
            startByte++;
        }


        if (patternFound == false) {
            // This is not an iBeacon
            Log.d(TAG, "This is not an iBeacon advertisment (no 0215 seen in bytes 4-7).  The bytes I see are: " + bytesToHex(scanData));
            return null;
        }

        IBeacon iBeacon = new IBeacon();

        iBeacon.major = (scanData[startByte + 20] & 0xff) * 0x100 + (scanData[startByte + 21] & 0xff);
        iBeacon.minor = (scanData[startByte + 22] & 0xff) * 0x100 + (scanData[startByte + 23] & 0xff);
        iBeacon.txPower = (int) scanData[startByte + 24]; // this one is signed
        iBeacon.rssi = rssi;

        // AirLocate:
        // 02 01 1a 1a ff 4c 00 02 15  # Apple's fixed iBeacon advertising prefix
        // e2 c5 6d b5 df fb 48 d2 b0 60 d0 f5 a7 10 96 e0 # iBeacon profile uuid
        // 00 00 # major
        // 00 00 # minor
        // c5 # The 2's complement of the calibrated Tx Power

        // Estimote:
        // 02 01 1a 11 07 2d 24 bf 16
        // 394b31ba3f486415ab376e5c0f09457374696d6f7465426561636f6e00000000000000000000000000000000000000000000000000

        byte[] proximityUuidBytes = new byte[16];
        System.arraycopy(scanData, startByte + 4, proximityUuidBytes, 0, 16);
        String hexString = bytesToHex(proximityUuidBytes);
        StringBuilder sb = new StringBuilder();
        sb.append(hexString.substring(0, 8));
        sb.append("-");
        sb.append(hexString.substring(8, 12));
        sb.append("-");
        sb.append(hexString.substring(12, 16));
        sb.append("-");
        sb.append(hexString.substring(16, 20));
        sb.append("-");
        sb.append(hexString.substring(20, 32));
        iBeacon.proximityUuid = sb.toString();

        iBeacon.proximity = calculateProximity(calculateAccuracy(iBeacon.txPower, iBeacon.rssi));

        if (device != null) {
            iBeacon.bluetoothAddress = device.getAddress();
        }

        return iBeacon;
    }

    protected static double calculateAccuracy(int txPower, double rssi) {
        if (rssi == 0) {
            return -1.0; // if we cannot determine accuracy, return -1.
        }

        if (IBeaconManager.debug) Log.d(TAG, "calculating accuracy based on rssi of " + rssi);


        double ratio = rssi * 1.0 / txPower;
        if (ratio < 1.0) {
            return Math.pow(ratio, 10);
        } else {
            double accuracy = (0.89976) * Math.pow(ratio, 7.7095) + 0.111;
            if (IBeaconManager.debug) Log.d(TAG, " avg rssi: " + rssi + " accuracy: " + accuracy);
            return accuracy;
        }
    }

    protected static String calculateProximity(double accuracy) {
        if (accuracy < 0) {
            return "UNKNOWN";
            // is this correct?  does proximity only show unknown when accuracy is negative?  I have seen cases where it returns unknown when
            // accuracy is -1;
        }
        if (accuracy < 0.5) {
            return "IMMEDIATE";
        }
        // forums say 3.0 is the near/far threshold, but it looks to be based on experience that this is 4.0
        if (accuracy <= 4.0) {
            return "NEAR";
        }
        // if it is > 4.0 meters, call it far
        return "FAR";

    }

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }


    public Beacons(ScanActivity scanActivity) {

    }

    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        IBeacon iBeacon = fromScanData(scanRecord, rssi, device);
        System.out.println(iBeacon);
        beaconMap.put(iBeacon.bluetoothAddress, iBeacon);

    }

}
