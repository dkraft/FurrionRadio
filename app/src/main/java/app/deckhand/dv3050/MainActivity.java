package app.deckhand.dv3050;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    private static final String TARGET = "C0:28:27:0D:B2:3C";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int BG = 0xFF14120F;
    private static final int SURFACE = 0xFF221E19;
    private static final int RAISED = 0xFF2C2822;
    private static final int FG = 0xFFF4EFE6;
    private static final int MUTED = 0xFFA39888;
    private static final int ACCENT = 0xFFF0A202;
    private static final int INK = 0xFF1A1406;
    private static final String[][] KEYS = {
            {"power", "Power"}, {"mute", "Mute"},
            {"vol-down", "Vol -"}, {"vol-up", "Vol +"}, {"mode", "Mode"}, {"band", "Band"},
            {"seek-down", "Seek -"}, {"seek-up", "Seek +"}, {"aps", "APS"}, {"scan", "Scan"},
            {"eq", "EQ"}, {"clock", "Clock"}, {"region", "Region"}, {"tm", "T-M"},
            {"n1", "1"}, {"n2", "2"}, {"n3", "3"}, {"n4", "4"},
            {"n5", "5"}, {"n6", "6"}, {"n7", "7"}, {"n8", "8"},
            {"n9", "9"}, {"n0", "0"}, {"play", "Play"}, {"stop", "Stop"},
            {"prev", "Prev"}, {"next", "Next"}, {"intro", "INT"}, {"repeat", "RPT"},
            {"random", "RDM"}, {"tel", "Tel"}, {"redial", "Redial"}, {"hangup", "Hang"},
            {"calllog", "Log"}, {"clear", "Clear"}
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    private final Map<String, BluetoothGattCharacteristic> chars = new HashMap<>();
    private final Map<String, TextView> valueViews = new HashMap<>();
    private final Map<String, BluetoothDevice> seen = new LinkedHashMap<>();
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            runOnUiThread(() -> upsert(result));
        }
    };

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice connectedDevice;
    private TextView statusView;
    private TextView lcd;
    private TextView logView;
    private LinearLayout deviceBox;
    private LinearLayout gattBox;
    private CheckBox showAll;
    private android.content.SharedPreferences prefs;
    private boolean busy;
    private int ticket;
    private boolean scanning;

    private interface Op {
        boolean start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("deckhand", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(root);

        TextView title = text("DECKHAND", 28, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        statusView = text("DV3050-4.0-523C", 14, MUTED);
        root.addView(statusView);

        LinearLayout row = row();
        row.addView(action("Scan", v -> startScan()));
        row.addView(action("Bond", v -> bond()));
        row.addView(action("Drop", v -> drop()));
        root.addView(row);

        showAll = new CheckBox(this);
        showAll.setText("Include other advertisers");
        showAll.setTextColor(MUTED);
        root.addView(showAll);

        deviceBox = column();
        root.addView(deviceBox);

        lcd = text("NOT LINKED\nFace keys need a mapped payload\nPIN 0000 if classic pair asks", 18, ACCENT);
        lcd.setTypeface(Typeface.MONOSPACE);
        lcd.setBackgroundColor(0xFF0C0B09);
        lcd.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(space());
        root.addView(lcd);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridLp.topMargin = dp(8);
        grid.setLayoutParams(gridLp);
        for (String[] key : KEYS) {
            Button b = face(key[1]);
            b.setOnClickListener(v -> onFace(key[0], key[1]));
            b.setOnLongClickListener(v -> {
                promptMap(key[0], key[1]);
                return true;
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(48);
            int span = "power".equals(key[0]) || "mute".equals(key[0]) ? 2 : 1;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(b, lp);
        }
        root.addView(grid);

        TextView gattTitle = text("Every characteristic", 18, FG);
        gattTitle.setPadding(0, dp(16), 0, dp(4));
        root.addView(gattTitle);
        root.addView(text("Read, listen, or send hex / \"text\" on each one. Set pipe, then a face key uses that characteristic.", 13, MUTED));
        gattBox = column();
        root.addView(gattBox);

        logView = text("", 12, MUTED);
        logView.setTypeface(Typeface.MONOSPACE);
        root.addView(space());
        root.addView(logView);
        setContentView(scroll);

        if (adapter == null || !adapter.isEnabled()) {
            statusView.setText("Bluetooth is off.");
        }
    }

    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            statusView.setText("Bluetooth is off.");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT
            }, 7);
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            statusView.setText("No LE scanner.");
            return;
        }
        seen.clear();
        deviceBox.removeAllViews();
        scanning = true;
        statusView.setText("Scanning 12s for DV3050 / A032 / " + TARGET);
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(null, settings, scanCallback);
        handler.postDelayed(this::stopScan, 12000);
    }

    private void stopScan() {
        if (!scanning || scanner == null) return;
        scanning = false;
        try {
            scanner.stopScan(scanCallback);
        } catch (Exception ignored) {
        }
        if (seen.isEmpty()) statusView.setText("Nothing matched. Try including other advertisers.");
        else statusView.setText(seen.size() + " advertiser" + (seen.size() == 1 ? "" : "s") + ". Tap to connect.");
    }

    private void upsert(ScanResult result) {
        BluetoothDevice device = result.getDevice();
        String addr = device.getAddress();
        String name = null;
        boolean a032 = false;
        if (result.getScanRecord() != null) {
            name = result.getScanRecord().getDeviceName();
            List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
            if (uuids != null) {
                for (ParcelUuid u : uuids) {
                    if (u.getUuid().toString().toLowerCase(Locale.US).contains("0000a032")) a032 = true;
                }
            }
        }
        if (name == null) name = device.getName();
        boolean hit = (name != null && name.toUpperCase(Locale.US).contains("DV3050"))
                || TARGET.equalsIgnoreCase(addr)
                || a032;
        if (!hit && (showAll == null || !showAll.isChecked())) return;
        boolean fresh = !seen.containsKey(addr);
        seen.put(addr, device);
        String label = (name == null ? "(no name)" : name) + "\n" + addr + "   " + result.getRssi() + " dBm"
                + (a032 ? "   A032" : "");
        if (!fresh) {
            for (int i = 0; i < deviceBox.getChildCount(); i++) {
                View child = deviceBox.getChildAt(i);
                if (addr.equals(child.getTag())) {
                    ((Button) child).setText(label);
                    return;
                }
            }
        }
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTag(addr);
        b.setText(label);
        b.setTextColor(hit ? ACCENT : FG);
        b.setBackgroundColor(SURFACE);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(v -> connect(device));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        deviceBox.addView(b, lp);
    }

    private void connect(BluetoothDevice device) {
        stopScan();
        dropGattOnly();
        connectedDevice = device;
        statusView.setText("Connecting " + device.getAddress());
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    private void bond() {
        BluetoothDevice device = connectedDevice;
        if (device == null && !seen.isEmpty()) device = seen.values().iterator().next();
        if (device == null) {
            toast("Scan and connect first.");
            return;
        }
        boolean started = device.createBond();
        toast(started
                ? "System bond dialog. If it asks, the DV3050 manual PIN is 0000."
                : "Bond did not start.");
        log(started ? "bond requested" : "bond failed to start");
    }

    private void drop() {
        dropGattOnly();
        connectedDevice = null;
        gattBox.removeAllViews();
        chars.clear();
        valueViews.clear();
        lcd.setText("NOT LINKED\nFace keys need a mapped payload\nPIN 0000 if classic pair asks");
        statusView.setText("Disconnected.");
        log("disconnect");
    }

    private void dropGattOnly() {
        ops.clear();
        busy = false;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            runOnUiThread(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    statusView.setText("Connected. Reading the attribute table…");
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    statusView.setText(status == BluetoothGatt.GATT_SUCCESS ? "Disconnected." : "Link dropped (" + status + ")");
                    lcd.setText("NOT LINKED");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            runOnUiThread(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    statusView.setText("Service discovery failed (" + status + ")");
                    return;
                }
                renderServices(g.getServices());
                statusView.setText(chars.size() + " characteristics on " + (connectedDevice == null ? "radio" : nameOf(connectedDevice)));
                lcd.setText("LIVE\n" + nameOf(connectedDevice) + "\n" + chars.size() + " characteristics");
                for (Map.Entry<String, BluetoothGattCharacteristic> entry : chars.entrySet()) {
                    int p = entry.getValue().getProperties();
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        enqueue(() -> gatt != null && gatt.readCharacteristic(entry.getValue()));
                    }
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value, int status) {
            runOnUiThread(() -> showValue(ch, value, status));
            done();
        }

        @Override
        @Deprecated
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (Build.VERSION.SDK_INT < 33) {
                runOnUiThread(() -> showValue(ch, ch.getValue(), status));
                done();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            log(status == BluetoothGatt.GATT_SUCCESS ? "write ok " + shortUuid(ch.getUuid()) : "write status " + status);
            done();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            runOnUiThread(() -> showValue(ch, value, BluetoothGatt.GATT_SUCCESS));
        }

        @Override
        @Deprecated
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] value = ch.getValue();
                runOnUiThread(() -> showValue(ch, value, BluetoothGatt.GATT_SUCCESS));
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            log(status == BluetoothGatt.GATT_SUCCESS ? "listening" : "notify setup " + status);
            done();
        }
    };

    private void renderServices(List<BluetoothGattService> services) {
        gattBox.removeAllViews();
        chars.clear();
        valueViews.clear();
        if (services == null) return;
        for (BluetoothGattService svc : services) {
            List<BluetoothGattCharacteristic> list = svc.getCharacteristics();
            for (BluetoothGattCharacteristic ch : list) {
                String key = shortUuid(svc.getUuid()) + "/" + shortUuid(ch.getUuid());
                chars.put(key, ch);
                gattBox.addView(charBlock(key, svc.getUuid(), ch));
            }
        }
        if (chars.isEmpty()) gattBox.addView(text("The radio published no characteristics.", 14, MUTED));
    }

    private View charBlock(String key, UUID service, BluetoothGattCharacteristic ch) {
        int p = ch.getProperties();
        boolean canRead = (p & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
        boolean canWrite = (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        boolean canWnr = (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        boolean canNotify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        boolean canIndicate = (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

        LinearLayout box = column();
        box.setBackgroundColor(SURFACE);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = dp(8);
        box.setLayoutParams(boxLp);

        box.addView(text(labelFor(ch.getUuid()), 16, FG));
        box.addView(text(key + "  " + propText(canRead, canWrite, canWnr, canNotify, canIndicate), 12, MUTED));
        TextView value = text("—", 14, ACCENT);
        value.setTypeface(Typeface.MONOSPACE);
        valueViews.put(key, value);
        box.addView(value);

        LinearLayout buttons = row();
        Button read = small("Read");
        read.setEnabled(canRead);
        read.setOnClickListener(v -> enqueue(() -> gatt != null && gatt.readCharacteristic(ch)));
        Button listen = small(canNotify || canIndicate ? "Listen" : "Listen");
        listen.setEnabled(canNotify || canIndicate);
        listen.setOnClickListener(v -> subscribe(ch, canIndicate && !canNotify));
        Button pipe = small("Pipe");
        pipe.setOnClickListener(v -> {
            prefs.edit().putString("pipe", key).apply();
            toast("Face keys write " + key);
            log("pipe " + key);
            lcd.setText("LIVE\n" + nameOf(connectedDevice) + "\nPIPE " + key);
        });
        buttons.addView(read);
        buttons.addView(listen);
        buttons.addView(pipe);
        box.addView(buttons);

        if (canWrite || canWnr) {
            EditText input = new EditText(this);
            input.setHint("01 0a or \"NEXT\"");
            input.setTextColor(FG);
            input.setHintTextColor(MUTED);
            input.setSingleLine(true);
            input.setBackgroundColor(BG);
            input.setPadding(dp(8), dp(8), dp(8), dp(8));
            Button send = small("Send");
            boolean[] withResponse = {canWrite && !canWnr};
            Button mode = small(withResponse[0] ? "WR" : "WN");
            mode.setOnClickListener(v -> {
                withResponse[0] = !withResponse[0];
                mode.setText(withResponse[0] ? "WR" : "WN");
            });
            send.setOnClickListener(v -> {
                byte[] data = parsePayload(String.valueOf(input.getText()));
                if (data == null) {
                    toast("Hex (01 0a) or a quoted string.");
                    return;
                }
                int type = withResponse[0]
                        ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                enqueue(() -> writeChar(ch, data, type));
            });
            LinearLayout sendRow = row();
            sendRow.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            sendRow.addView(mode);
            sendRow.addView(send);
            box.addView(sendRow);
        }
        return box;
    }

    private void subscribe(BluetoothGattCharacteristic ch, boolean indicate) {
        if (gatt == null) return;
        boolean ok = gatt.setCharacteristicNotification(ch, true);
        if (!ok) {
            toast("Notifications were refused.");
            return;
        }
        BluetoothGattDescriptor desc = ch.getDescriptor(CCCD);
        if (desc == null) {
            log("no CCCD on " + shortUuid(ch.getUuid()));
            return;
        }
        byte[] flag = indicate
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        enqueue(() -> writeDesc(desc, flag));
    }

    private boolean writeChar(BluetoothGattCharacteristic ch, byte[] data, int writeType) {
        if (gatt == null) return false;
        int props = ch.getProperties();
        boolean canWrite = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        boolean canWnr = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        if (!canWrite && !canWnr) return false;
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE && !canWnr) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        }
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT && !canWrite) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeCharacteristic(ch, data, writeType) == BluetoothStatusCodes.SUCCESS;
        }
        ch.setValue(data);
        ch.setWriteType(writeType);
        return gatt.writeCharacteristic(ch);
    }

    private boolean writeDesc(BluetoothGattDescriptor desc, byte[] value) {
        if (gatt == null) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS;
        }
        desc.setValue(value);
        return gatt.writeDescriptor(desc);
    }

    private void showValue(BluetoothGattCharacteristic ch, byte[] value, int status) {
        String key = findKey(ch);
        String hex = status == BluetoothGatt.GATT_SUCCESS ? toHex(value) : "status " + status;
        TextView view = key == null ? null : valueViews.get(key);
        if (view != null) view.setText(hex.isEmpty() ? "(empty)" : hex);
        log((key == null ? shortUuid(ch.getUuid()) : key) + " " + hex);
        if (gatt != null) {
            lcd.setText("LIVE\n" + nameOf(connectedDevice) + "\n" + (hex.isEmpty() ? "(empty)" : hex));
        }
    }

    private String findKey(BluetoothGattCharacteristic ch) {
        for (Map.Entry<String, BluetoothGattCharacteristic> entry : chars.entrySet()) {
            if (entry.getValue() == ch) return entry.getKey();
        }
        for (Map.Entry<String, BluetoothGattCharacteristic> entry : chars.entrySet()) {
            if (entry.getValue().getUuid().equals(ch.getUuid())) return entry.getKey();
        }
        return null;
    }

    private void onFace(String id, String label) {
        if (gatt == null) {
            toast("Not connected.");
            promptMap(id, label);
            return;
        }
        String payload = prefs.getString("m_" + id, "");
        String pipe = prefs.getString("pipe", "");
        if (payload == null || payload.isEmpty() || pipe == null || pipe.isEmpty() || chars.get(pipe) == null) {
            promptMap(id, label);
            return;
        }
        byte[] data = parsePayload(payload);
        if (data == null) {
            toast("Saved payload is not valid hex or a quoted string.");
            return;
        }
        boolean resp = prefs.getBoolean("resp_" + id, false);
        BluetoothGattCharacteristic ch = chars.get(pipe);
        int type = resp
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        enqueue(() -> writeChar(ch, data, type));
        log("key " + label);
    }

    private void promptMap(String id, String label) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(prefs.getString("m_" + id, ""));
        input.setTextColor(FG);
        input.setHint("01 0a or \"NEXT\"");
        CheckBox resp = new CheckBox(this);
        resp.setText("Write with response");
        resp.setTextColor(FG);
        resp.setChecked(prefs.getBoolean("resp_" + id, false));
        LinearLayout wrap = column();
        wrap.setPadding(dp(16), dp(8), dp(16), 0);
        wrap.addView(input);
        wrap.addView(resp);
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setMessage("Saved on this phone. Sent to the pipe characteristic.")
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit()
                            .putString("m_" + id, String.valueOf(input.getText()).trim())
                            .putBoolean("resp_" + id, resp.isChecked())
                            .apply();
                    toast(label + " saved");
                })
                .setNeutralButton("Clear", (d, w) -> prefs.edit().remove("m_" + id).apply())
                .setNegativeButton("Close", null)
                .show();
    }

    private void enqueue(Op op) {
        runOnUiThread(() -> {
            ops.add(op);
            pump();
        });
    }

    private void pump() {
        if (busy) return;
        Op op = ops.poll();
        if (op == null) return;
        busy = true;
        int mine = ++ticket;
        handler.postDelayed(() -> {
            if (busy && ticket == mine) {
                busy = false;
                ticket++;
                log("GATT op timed out");
                pump();
            }
        }, 3000);
        boolean ok;
        try {
            ok = op.start();
        } catch (Exception e) {
            ok = false;
            log(e.getMessage() == null ? "GATT error" : e.getMessage());
        }
        if (!ok) {
            busy = false;
            ticket++;
            pump();
        }
    }

    private void done() {
        runOnUiThread(() -> {
            if (!busy) return;
            busy = false;
            ticket++;
            pump();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else if (requestCode == 7) {
            statusView.setText("Bluetooth permission is required to reach the radio.");
        }
    }

    @Override
    protected void onDestroy() {
        stopScan();
        dropGattOnly();
        super.onDestroy();
    }

    private static byte[] parsePayload(String input) {
        if (input == null) return null;
        String t = input.trim();
        if (t.isEmpty()) return null;
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1).getBytes(StandardCharsets.UTF_8);
        }
        String s = t.replace("0x", "").replace("0X", "").replaceAll("[^0-9a-fA-F]", "");
        if (s.isEmpty() || (s.length() % 2) != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    private static String shortUuid(UUID uuid) {
        String u = uuid.toString().toLowerCase(Locale.US);
        if (u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb")) return u.substring(4, 8);
        return u;
    }

    private static String labelFor(UUID uuid) {
        switch (shortUuid(uuid)) {
            case "1800": return "Generic Access";
            case "1801": return "Generic Attribute";
            case "1804": return "Tx Power";
            case "180a": return "Device Information";
            case "180f": return "Battery";
            case "a032": return "Advertised vendor service";
            case "2a00": return "Device Name";
            case "2a01": return "Appearance";
            case "2a04": return "Preferred Connection Parameters";
            case "2a19": return "Battery Level";
            case "2a24": return "Model Number";
            case "2a25": return "Serial Number";
            case "2a26": return "Firmware Revision";
            case "2a27": return "Hardware Revision";
            case "2a29": return "Manufacturer Name";
            case "2a50": return "PnP ID";
            default: return "Unrecognized";
        }
    }

    private static String propText(boolean r, boolean w, boolean wnr, boolean n, boolean i) {
        StringBuilder sb = new StringBuilder();
        if (r) sb.append("read ");
        if (w) sb.append("write ");
        if (wnr) sb.append("write-nr ");
        if (n) sb.append("notify ");
        if (i) sb.append("indicate ");
        return sb.toString().trim();
    }

    private String nameOf(BluetoothDevice device) {
        if (device == null) return HEARD_NAME;
        String n = device.getName();
        return n == null ? device.getAddress() : n;
    }

    private static final String HEARD_NAME = "DV3050-4.0-523C";

    private void log(String line) {
        runOnUiThread(() -> {
            if (logView == null || line == null) return;
            String prev = String.valueOf(logView.getText());
            String next = line + (prev.isEmpty() ? "" : "\n" + prev);
            if (next.length() > 1800) next = next.substring(0, 1800);
            logView.setText(next);
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    private Button action(String label, View.OnClickListener click) {
        Button b = face(label);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(3), dp(8), dp(3), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private Button face(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(FG);
        b.setBackgroundColor(RAISED);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return b;
    }

    private Button small(String label) {
        Button b = face(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(3), dp(6), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(10)));
        return v;
    }
}
