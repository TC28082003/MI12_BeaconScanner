package com.example.mi12_beaconscanner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.os.CountDownTimer;
import android.widget.ProgressBar;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
//import android.os.Environment;
 import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MainActivity extends AppCompatActivity implements BeaconConsumer {

    private BeaconManager beaconManager;
    private static final String TAG = "BeaconScanner";
    private static final int PERMISSION_REQUEST_CODE = 123;
    private Region beaconRegion;

    private BeaconAdapter beaconAdapter;

    private EditText xCoordinate, yCoordinate;
    private Button saveButton;
    private SwitchCompat modeSwitch;
    private Button deleteCsvButton;
    private View learningLayout;
    private View localizationLayout;
    private TextView csvContentTextView;
    private Button toggleCsvButton;
    private ProgressBar learningProgressBar, localizationProgressBar;
    private TextView learningCountdownText, localizationCountdownText;
    private CountDownTimer activeCountdownTimer;
    private final Map<String, List<Integer>> localizationBuffer = new HashMap<>();
    //private final long lastLocalizationUpdateTime = 0;
    //private static final long LOCALIZATION_UPDATE_INTERVAL = 30000;
    //private boolean isLocalizationMode = false;
    private Button startLocalizationButton;
    private boolean isLocating = false;
    private boolean isTracking = false;
    private float currentX = -1f;
    private float currentY = -1f;
    private static final float ALPHA = 0.2f;
    private boolean isRecording = false;
    private long lastUiupdate = 0;
    private static final long UI_UPDATE_INTERVAL = 250;
    //private long recordingStartTime;
    private final Map<String, List<Integer>> recordingData = new HashMap<>();
    private final java.util.HashSet<String> selectedBeacons = new java.util.HashSet<>();
    private final List<Fingerprint> fingerprintMap = new ArrayList<>();
    public static class Fingerprint {
        public final double x;
        public final double y;
        public final Map<String, Double> rssiMap;

        public Fingerprint(double x, double y, Map<String, Double> rssiMap) {
            this.x = x;
            this.y = y;
            this.rssiMap = rssiMap;
        }
    }
    public static class BeaconWrapper {
        public Beacon beacon;
        public long lastSeen;

        public BeaconWrapper(Beacon beacon) {
            this.beacon = beacon;
            this.lastSeen = System.currentTimeMillis();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BeaconWrapper that = (BeaconWrapper) o;
            return beacon.equals(that.beacon);
        }

        @Override
        public int hashCode() {
            return Objects.hash(beacon);
        }
    }

    private final LinkedHashMap<String, BeaconWrapper> beaconMap = new LinkedHashMap<>();
    private final ArrayList<Beacon> beaconList = new ArrayList<>();
    private final long STALE_BEACON_TIMEOUT = 60000; // 60s


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        learningProgressBar = findViewById(R.id.learningProgressBar);
        learningCountdownText = findViewById(R.id.learningCountdownText);
        localizationProgressBar = findViewById(R.id.localizationProgressBar);
        localizationCountdownText = findViewById(R.id.localizationCountdownText);
        startLocalizationButton = findViewById(R.id.startLocalizationButton);

        ListView beaconListView = findViewById(R.id.beaconListView);
        beaconAdapter = new BeaconAdapter(this, beaconList);
        beaconListView.setAdapter(beaconAdapter);
        beaconListView.setOnItemClickListener((parent, view, position, id) -> {
            Beacon selectedBeacon = beaconList.get(position);
            String beaconAddress = selectedBeacon.getBluetoothAddress();
            Log.d(TAG, "SÉLECTION: L'adresse " + beaconAddress + " a été ajoutée/retirée.");
            if (selectedBeacons.contains(beaconAddress)) {
                selectedBeacons.remove(beaconAddress); // Si déjà sélectionné, on le retire
            } else {
                selectedBeacons.add(beaconAddress); // Sinon, on l'ajoute
            }

            // On notifie l'adaptateur que les données ont changé pour qu'il redessine la liste
            beaconAdapter.notifyDataSetChanged();
        });
        xCoordinate = findViewById(R.id.xCoordinate);
        yCoordinate = findViewById(R.id.yCoordinate);
        saveButton = findViewById(R.id.saveButton);
        modeSwitch = findViewById(R.id.modeSwitch);
        deleteCsvButton = findViewById(R.id.deleteCsvButton);
        toggleCsvButton = findViewById(R.id.toggleCsvButton);
        learningLayout = findViewById(R.id.learningLayout);
        localizationLayout = findViewById(R.id.localizationLayout);
        csvContentTextView = findViewById(R.id.csvContentTextView);
        setupModeSwitch();
        setupLearningUI();
        setupDeleteButton();
        setupToggleCsvButton();
        setupLocalizationUI();

        beaconRegion = new Region("myRangingUniqueId", null, null, null);
        beaconManager = BeaconManager.getInstanceForApplication(this);

        BeaconManager.setRssiFilterImplClass(org.altbeacon.beacon.service.ArmaRssiFilter.class);

        beaconManager.setEnableScheduledScanJobs(false);
        beaconManager.setBackgroundBetweenScanPeriod(1100L);
        beaconManager.setBackgroundScanPeriod(10000L);

        beaconManager.setForegroundScanPeriod(100L);
        // Temps de pause entre les scans en millisecondes
        beaconManager.setForegroundBetweenScanPeriod(0L);
        try {
            beaconManager.updateScanPeriods();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"));
    // beaconManager.getBeaconParsers().add(new BeaconParser().
        //        setBeaconLayout("m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"));
        checkAndRequestPermissions();
    }

    private void setupLearningUI() {
        saveButton.setOnClickListener(v -> {
            String xStr = xCoordinate.getText().toString();
            String yStr = yCoordinate.getText().toString();

            if (xStr.isEmpty() || yStr.isEmpty()) {
                Toast.makeText(this, "Veuillez entrer les coordonnées X et Y", Toast.LENGTH_SHORT).show();
                return;
            }

            saveButton.setEnabled(false);
            xCoordinate.setEnabled(false);
            yCoordinate.setEnabled(false);

            isRecording = true;
            recordingData.clear();
            Toast.makeText(this, "Enregistrement pendant 30s...", Toast.LENGTH_SHORT).show();

            startCountdownTimer(40000, learningCountdownText, learningProgressBar, () -> {

                isRecording = false;
                processAndSaveRecording();

                saveButton.setEnabled(true);
                xCoordinate.setEnabled(true);
                yCoordinate.setEnabled(true);

                learningProgressBar.setVisibility(View.GONE);
                learningCountdownText.setVisibility(View.GONE);
            });
        });
    }

    /*private void setupModeSwitch() {
        modeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (activeCountdownTimer != null) {
                activeCountdownTimer.cancel();
            }

            if (isChecked) {
                modeSwitch.setText("Mode Localisation");
                learningLayout.setVisibility(View.GONE);
                localizationLayout.setVisibility(View.VISIBLE);
                loadAndDisplayCsvContent();
                loadFingerprintData();

                //startLocalizationCycle();

            } else {
                modeSwitch.setText("Mode Apprentissage");
                learningLayout.setVisibility(View.VISIBLE);
                localizationLayout.setVisibility(View.GONE);

                localizationProgressBar.setVisibility(View.GONE);
                localizationCountdownText.setVisibility(View.GONE);
            }
        });
    } */
    @SuppressLint("SetTextI18n")
    private void setupModeSwitch() {
        modeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (activeCountdownTimer != null) {
                activeCountdownTimer.cancel();
            }
            // Reset state
            isLocating = false;
            learningProgressBar.setVisibility(View.GONE);
            localizationProgressBar.setVisibility(View.GONE);
            isTracking = false;
            startLocalizationButton.setText("Démarrer le suivi");

            if (isChecked) {
                // Mode Localisation
                modeSwitch.setText("Mode Localisation");
                learningLayout.setVisibility(View.GONE);
                localizationLayout.setVisibility(View.VISIBLE);

                // Reset UI
                startLocalizationButton.setVisibility(View.VISIBLE);

                // Load data
                loadAndDisplayCsvContent();
                loadFingerprintData();

            } else {
                // Mode Apprentissage
                modeSwitch.setText("Mode Apprentissage");
                learningLayout.setVisibility(View.VISIBLE);
                localizationLayout.setVisibility(View.GONE);
            }
        });
    }

    /*private void setupLocalizationUI() {
        startLocalizationButton.setOnClickListener(v -> {
            startLocalizationButton.setEnabled(false);
            toggleCsvButton.setEnabled(false);

            localizationBuffer.clear();
            isLocating = true;

            Toast.makeText(this, "Début de l'estimation (30s)...", Toast.LENGTH_SHORT).show();

            startCountdownTimer(30000, localizationCountdownText, localizationProgressBar, () -> {
                isLocating = false;

                performPositionCalculation();

                startLocalizationButton.setEnabled(true);
                toggleCsvButton.setEnabled(true);

                localizationProgressBar.setVisibility(View.GONE);
                localizationCountdownText.setVisibility(View.GONE);
            });
        });
    }

     */

    @SuppressLint("SetTextI18n")
    private void setupLocalizationUI() {
        startLocalizationButton.setOnClickListener(v -> {
            if (!isTracking) {
                isTracking = true;
                startLocalizationButton.setText("Arrêter le suivi");

                currentX = -1f;
                currentY = -1f;

                Toast.makeText(this, "Suivi en temps réel activé", Toast.LENGTH_SHORT).show();

                localizationProgressBar.setVisibility(View.GONE);
                localizationCountdownText.setVisibility(View.GONE);

            } else {
                isTracking = false;
                startLocalizationButton.setText("Démarrer le suivi");

                Toast.makeText(this, "Suivi arrêté", Toast.LENGTH_SHORT).show();
            }
        });
    }
    /*@SuppressLint("SetTextI18n")
    private void performPositionCalculation() {
        Map<String, Integer> stabilizedRssiMap = new HashMap<>();

        Log.d(TAG, "--- FIN DE L'ESTIMATION ---");

        for (Map.Entry<String, List<Integer>> entry : localizationBuffer.entrySet()) {
            String beaconKey = entry.getKey();
            List<Integer> rssiList = entry.getValue();

            double median = calculateMedian(rssiList);
            stabilizedRssiMap.put(beaconKey, (int) median);

            Log.d(TAG, "Beacon: " + beaconKey
                    + " | List (" + rssiList.size() + "): " + rssiList.toString()
                    + " | Median: " + median);
        }

        android.graphics.PointF estimatedPosition = estimatePosition(stabilizedRssiMap);

        TextView posTextView = findViewById(R.id.estimatedPositionTextView);

        if (estimatedPosition != null) {
            posTextView.setText(String.format(java.util.Locale.US, "(%.2f, %.2f)", estimatedPosition.x, estimatedPosition.y));
        } else {
            posTextView.setText("Données insuffisantes / Hors zone.");
        }
    }

     */
    //private void startLocalizationCycle() {
     //   if (!modeSwitch.isChecked()) return;
//
   //      startCountdownTimer(LOCALIZATION_UPDATE_INTERVAL, localizationCountdownText, localizationProgressBar, () -> {
     //       startLocalizationCycle();
       // });
    //}
    @SuppressLint("SetTextI18n")
    private void setupToggleCsvButton(){
        toggleCsvButton.setOnClickListener(v -> {
        if (csvContentTextView.getVisibility() == View.VISIBLE) {
            csvContentTextView.setVisibility(View.GONE);
            toggleCsvButton.setText("Afficher/Masquer");
            toggleCsvButton.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_sort_by_size, 0, 0, 0);
        } else {
            csvContentTextView.setVisibility(View.VISIBLE);
            toggleCsvButton.setText("Afficher/Masquer");
            loadAndDisplayCsvContent();
            toggleCsvButton.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_close_clear_cancel, 0, 0, 0);
        }
    });
    }
    @SuppressLint("SetTextI18n")
    private void setupDeleteButton() {
        deleteCsvButton.setOnClickListener(v -> {
            File file = new File(getExternalFilesDir(null), "fingerprints.csv");

            if (file.exists()) {
                if (file.delete()) {
                    Toast.makeText(this, "Fichier fingerprints.csv réinitialisé.", Toast.LENGTH_SHORT).show();
                    csvContentTextView.setText("Fichier fingerprints.csv non trouvé !");
                    allKnownBeaconKeys.clear();
                } else {
                    Toast.makeText(this, "Erreur lors de la réinitialisation du fichier.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Le fichier n'existe pas.", Toast.LENGTH_SHORT).show();
            }
        });
    }
    private static class FingerprintDistance implements Comparable<FingerprintDistance> {
        public final Fingerprint fingerprint;
        public final double distance;

        public FingerprintDistance(Fingerprint fingerprint, double distance) {
            this.fingerprint = fingerprint;
            this.distance = distance;
        }

        @Override
        public int compareTo(FingerprintDistance other) {
            return Double.compare(this.distance, other.distance);
        }
    }
    /*private android.graphics.PointF estimatePosition(Map<String, Integer> currentRssiMap) {
        if (fingerprintMap.isEmpty()) {
            return null; // Pas de données pour comparer
        }

        List<FingerprintDistance> distances = new ArrayList<>();

        // Étape 1: Calculer la distance pour chaque empreinte de la carte
        for (Fingerprint fp : fingerprintMap) {
            double sumOfSquares = 0.0;
            int commonBeacons = 0;
            // On ne compare que les beacons présents dans l'en-tête de l'empreinte
            //int i=1;
            for (String address : fp.rssiMap.keySet()) {
                double savedRssi = fp.rssiMap.get(address);
                Integer currentRssiValue = currentRssiMap.get(address);
                double currentRssi = (currentRssiValue != null) ? currentRssiValue : -100.0; // Valeur par défaut si null
                sumOfSquares += Math.pow(savedRssi - currentRssi, 2);
                if (currentRssiValue != null) {
                    commonBeacons++;
                }
              //  i +=i;
            }
            double distance = Math.sqrt(sumOfSquares);
            Log.d(TAG, String.format("Khoảng cách tới (%.1f, %.1f) là: %.2f", fp.x, fp.y, distance));
            if (commonBeacons > 0) {
                distances.add(new FingerprintDistance(fp, Math.sqrt(sumOfSquares)));
            }
            distances.add(new FingerprintDistance(fp, Math.sqrt(sumOfSquares)));
        }

        // Étape 2: Trier pour trouver les plus proches
        Collections.sort(distances);

        // Étape 3: Faire la moyenne des 'k' plus proches voisins
        int k = 1;
        double sumX = 0.0;
        double sumY = 0.0;
        //double totalWeight = 0.0;

        int count = Math.min(k, distances.size()); // S'assurer de ne pas dépasser la taille de la liste

        if (count == 0) return null;

        for (int i = 0; i < count; i++) {
            FingerprintDistance fd = distances.get(i);
            double x = fd.fingerprint.x;
            double y = fd.fingerprint.y;

            //double weight = 1.0 / (fd.distance + 1e-6);

            sumX += x;// * weight;
            sumY += y;//* weight;
            //totalWeight += weight;
        }

        return new android.graphics.PointF((float) (sumX), (float) (sumY));
    }*/



    private android.graphics.PointF estimatePosition(Map<String, Integer> currentRssiMap) {
        if (fingerprintMap.isEmpty()) {
            return null;
        }

        java.util.Set<String> allowedBeacons = fingerprintMap.get(0).rssiMap.keySet();
        List<FingerprintDistance> distances = new ArrayList<>();

        for (Fingerprint fp : fingerprintMap) {
            double sumOfSquares = 0.0;
            int commonBeacons = 0;

            for (String address : fp.rssiMap.keySet()) {
                double savedRssi = fp.rssiMap.get(address);
                Integer currentRssiValue = currentRssiMap.get(address);
                double currentRssi = (currentRssiValue != null) ? currentRssiValue : -100.0;

                sumOfSquares += Math.pow(savedRssi - currentRssi, 2);

                if (currentRssiValue != null) {
                    commonBeacons++;
                }
            }

            if (commonBeacons > 0) {
                distances.add(new FingerprintDistance(fp, Math.sqrt(sumOfSquares)));
            }
        }

        Collections.sort(distances);

        int k = 3;
        int count = Math.min(k, distances.size());

        if (count == 0) return null;

        List<FingerprintDistance> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(distances.get(i));
        }

        int[] votes = new int[count];
        for (String beaconKey : currentRssiMap.keySet()) {
            if (!allowedBeacons.contains(beaconKey)) {
                continue;
            }
            double currentRssi = currentRssiMap.get(beaconKey);

            int bestCandidateIndex = -1;
            double minDiff = Double.MAX_VALUE;

            for (int i = 0; i < count; i++) {
                Fingerprint fp = candidates.get(i).fingerprint;

                double candidateRssi = fp.rssiMap.containsKey(beaconKey) ? fp.rssiMap.get(beaconKey) : -100.0;

                double diff = Math.abs(currentRssi - candidateRssi);

                if (diff < minDiff) {
                    minDiff = diff;
                    bestCandidateIndex = i;
                }
            }

            if (bestCandidateIndex != -1) {
                votes[bestCandidateIndex]++;
                Log.d(TAG, "Beacon " + beaconKey + " vote for Candidate " + bestCandidateIndex + " (Diff: " + minDiff + ")");
            }
        }

        int winnerIndex = 0;
        int maxVotes = -1;

        Log.d(TAG, "--- Final results ---");
        for (int i = 0; i < count; i++) {
            Fingerprint fp = candidates.get(i).fingerprint;
            Log.d(TAG, "Candidate " + i + " (" + fp.x + ", " + fp.y + ") - Votes: " + votes[i] + " - Dist: " + candidates.get(i).distance);

            if (votes[i] > maxVotes) {
                maxVotes = votes[i];
                winnerIndex = i;
            }
        }

        Fingerprint winner = candidates.get(winnerIndex).fingerprint;

        return new android.graphics.PointF((float) winner.x, (float) winner.y);
    }

    private void loadFingerprintData() {
        fingerprintMap.clear(); // Vider les anciennes données
        File file = new File(getExternalFilesDir(null), "fingerprints.csv");
        if (!file.exists()) {
            Toast.makeText(this, "Fichier fingerprints.csv non trouvé.", Toast.LENGTH_SHORT).show();
            return;
        }

        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

            // Lire l'en-tête pour obtenir les adresses des beacons
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            String[] headerParts = headerLine.split(",");
            List<String> beaconAddresses = new ArrayList<>();
            for (int i = 2; i < headerParts.length; i++) { // Commence à 2 pour sauter "x,y"
                beaconAddresses.add(headerParts[i]);
            }

            // Lire le reste des lignes (les données)
            String dataLine;
            while ((dataLine = reader.readLine()) != null) {
                String[] dataParts = dataLine.split(",");
                double x = Double.parseDouble(dataParts[0]);
                double y = Double.parseDouble(dataParts[1]);

                Map<String, Double> rssiMap = new HashMap<>();
                for (int i = 0; i < beaconAddresses.size(); i++) {
                    String address = beaconAddresses.get(i);
                    double rssi = Double.parseDouble(dataParts[i + 2]);
                    rssiMap.put(address, rssi);
                }
                fingerprintMap.add(new Fingerprint(x, y, rssiMap));
            }
            Toast.makeText(this, fingerprintMap.size() + " empreintes chargées.", Toast.LENGTH_SHORT).show();

        } catch (IOException | NumberFormatException e) {
            Log.e(TAG, "Erreur lors du chargement des empreintes", e);
            Toast.makeText(this, "Erreur de lecture du fichier CSV.", Toast.LENGTH_SHORT).show();
        }
    }
    @SuppressLint("SetTextI18n")
    private void loadAndDisplayCsvContent() {
        File file = new File(getExternalFilesDir(null), "fingerprints.csv");
        if (!file.exists()) {
            csvContentTextView.setText("Fichier fingerprints.csv non trouvé !");
            Toast.makeText(this, "Veuillez d'abord enregistrer des points.", Toast.LENGTH_LONG).show();
            return;
        }

        StringBuilder contentBuilder = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

            String line;
            while ((line = reader.readLine()) != null) {
                contentBuilder.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Erreur lors de la lecture du fichier", e);
            csvContentTextView.setText("Erreur de lecture du fichier.");
            Toast.makeText(this, "Erreur de lecture du fichier.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (contentBuilder.length() > 0) {
            csvContentTextView.setText(contentBuilder.toString());
            Toast.makeText(this, "Fichier CSV chargé avec succès.", Toast.LENGTH_SHORT).show();
        } else {
            csvContentTextView.setText("Le fichier fingerprints.csv est vide.");
        }
    }
    private double calculateMedian(List<Integer> rssiList) {
        if (rssiList == null || rssiList.isEmpty()) {
            return -100.00;
        }

        List<Integer> sortedList = new ArrayList<>(rssiList);
        Collections.sort(sortedList);

        int size = sortedList.size();
        if (size % 2 == 1) {
            return sortedList.get(size / 2);
        } else {
            return (sortedList.get(size / 2 - 1) + sortedList.get(size / 2)) / 2.0;
        }
    }
    @Override
    public void onBeaconServiceConnect() {
        beaconManager.addRangeNotifier(new RangeNotifier() {
            @Override
            public void didRangeBeaconsInRegion(Collection<Beacon> beacons, Region region) {
                for (Beacon beacon : beacons) {
                    String beaconKey = beacon.getBluetoothAddress();
                    if (beaconMap.containsKey(beaconKey)) {
                        BeaconWrapper wrapper = beaconMap.get(beaconKey);
                        if (wrapper != null) {
                            wrapper.beacon = beacon;
                            wrapper.lastSeen = System.currentTimeMillis();
                        }
                    } else {
                        beaconMap.put(beaconKey, new BeaconWrapper(beacon));
                    }
                }

                Iterator<Map.Entry<String, BeaconWrapper>> iterator = beaconMap.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, BeaconWrapper> entry = iterator.next();
                    if (System.currentTimeMillis() - entry.getValue().lastSeen > STALE_BEACON_TIMEOUT) {
                        iterator.remove();
                    }
                }

                long now = System.currentTimeMillis();
                if(now - lastUiupdate > UI_UPDATE_INTERVAL){
                    lastUiupdate = now;
                    runOnUiThread(() -> {

                        beaconList.clear();
                        for (BeaconWrapper wrapper : beaconMap.values()) {
                            beaconList.add(wrapper.beacon);
                        }
                        beaconAdapter.notifyDataSetChanged();

                        if (modeSwitch.isChecked() && isTracking) {
                            performRealTimeEstimationFromList();
                        }
                    });
                }

                if (isRecording) {
                    for (Beacon beacon : beacons) {
                        String key = beacon.getBluetoothAddress();
                        if (!recordingData.containsKey(key)) {
                            recordingData.put(key, new ArrayList<>());
                        }
                        recordingData.get(key).add(beacon.getRssi());
                    }
                }
            }

        });

        try {
            beaconManager.startRangingBeaconsInRegion(beaconRegion);
        } catch (RemoteException e) {
            Log.e(TAG, "Error starting ranging", e);
        }
    }

    /** @noinspection MismatchedQueryAndUpdateOfCollection*/
    private final List<String> allKnownBeaconKeys = new ArrayList<>();

    private void performRealTimeEstimationFromList() {
        Map<String, Integer> screenRssiMap = new HashMap<>();

        for (Beacon beacon : beaconList) {
            screenRssiMap.put(beacon.getBluetoothAddress(), beacon.getRssi());
        }

        if (screenRssiMap.isEmpty()) return;
        Log.d(TAG, ">>> ESTIME DATA (Realtime): " + screenRssiMap.toString());
        android.graphics.PointF rawPosition = estimatePosition(screenRssiMap);

        updateRealTimeUI(rawPosition);
    }
    /*private void processAndSaveRecording() {
        String x = xCoordinate.getText().toString();
        String y = yCoordinate.getText().toString();

        // On vérifie si au moins un beacon a été sélectionné
        if (selectedBeacons.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(this, "Veuillez sélectionner au moins un beacon.", Toast.LENGTH_LONG).show());
            return;
        }

        // On trie les adresses pour un ordre cohérent
        List<String> sortedSelectedBeacons = new ArrayList<>(selectedBeacons);
        Collections.sort(sortedSelectedBeacons);
        Log.d(TAG, "SAUVEGARDE: Beacons SÉLECTIONNÉS -> " + sortedSelectedBeacons);
        Log.d(TAG, "SAUVEGARDE: Beacons ENREGISTRÉS -> " + recordingData.keySet());

        for (Map.Entry<String, List<Integer>> entry : recordingData.entrySet()) {
            String beaconAddress = entry.getKey();
            List<Integer> rssiList = entry.getValue();

            Log.d(TAG, "Beacon: " + beaconAddress + " (" + rssiList.size() + " lectures): " + rssiList);
        }
        Log.d(TAG, "-------------------------------------");

        // Construction de l'en-tête à partir des beacons SÉLECTIONNÉS
        StringBuilder headerBuilder = new StringBuilder("x,y");
        for (String key : sortedSelectedBeacons) {
            headerBuilder.append(",").append(key);
        }
        String header = headerBuilder.toString();

        // Construction de la ligne de données
        StringBuilder dataBuilder = new StringBuilder(x + "," + y);
        for (String key : sortedSelectedBeacons) {
            dataBuilder.append(",");
            if (recordingData.containsKey(key)) {
                List<Integer> rssiList = recordingData.get(key);
                double medianRssi = calculateMedian(rssiList);
                dataBuilder.append(String.format(java.util.Locale.US, "%.2f", medianRssi));
            } else {
                // Si un beacon sélectionné n'a pas été détecté pendant l'enregistrement
                dataBuilder.append("-100.00");
            }
        }
        // J'ai retiré le " (xx lectures)\n" pour garder un format CSV propre
        String dataLine = dataBuilder.toString();

        // Appel de la méthode de sauvegarde (qui reste inchangée)
        saveFingerprintToFile(header, dataLine);

        Log.d(TAG, "--- Empreinte Enregistrée ---");
        Log.d(TAG, "Header: " + header);
        Log.d(TAG, "Data: " + dataLine);

        runOnUiThread(() -> Toast.makeText(this, "Point (" + x + ", " + y + ") enregistré !", Toast.LENGTH_LONG).show());
    }

     */
    @SuppressLint("DefaultLocale")
    private void updateRealTimeUI(android.graphics.PointF rawPosition) {
        TextView posTextView = findViewById(R.id.estimatedPositionTextView);

        if (rawPosition != null) {
            if (currentX == -1f || currentY == -1f) {
                currentX = rawPosition.x;
                currentY = rawPosition.y;
            } else {
                // New = Old + alpha * (Target - Old)
                currentX = currentX + ALPHA * (rawPosition.x - currentX);
                currentY = currentY + ALPHA * (rawPosition.y - currentY);
            }

            posTextView.setText(String.format("(%.2f, %.2f)", currentX, currentY));
        }
    }
    private void processAndSaveRecording() {
        String x = xCoordinate.getText().toString();
        String y = yCoordinate.getText().toString();

        if (selectedBeacons.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(this, "Veuillez sélectionner au moins un beacon.", Toast.LENGTH_LONG).show());
            return;
        }

        List<String> sortedSelectedBeacons = new ArrayList<>(selectedBeacons);
        Collections.sort(sortedSelectedBeacons);
        Log.d(TAG, "SAUVEGARDE: Beacons SÉLECTIONNÉS -> " + sortedSelectedBeacons);
        Log.d(TAG, "SAUVEGARDE: Beacons ENREGISTRÉS -> " + recordingData.keySet());
        for (Map.Entry<String, List<Integer>> entry : recordingData.entrySet()) {
            String beaconAddress = entry.getKey();
            List<Integer> rssiList = entry.getValue();

            Log.d(TAG, "Beacon: " + beaconAddress + " (" + rssiList.size() + " lectures): " + rssiList);
        }
        StringBuilder headerBuilder = new StringBuilder("x,y");
        for (String key : sortedSelectedBeacons) {
            headerBuilder.append(",").append(key);
        }
        String header = headerBuilder.toString();

        StringBuilder dataBuilder = new StringBuilder(x + "," + y);
        for (String key : sortedSelectedBeacons) {
            dataBuilder.append(",");
            if (recordingData.containsKey(key)) {
                List<Integer> rssiList = recordingData.get(key);
                double medianRssi = calculateMedian(rssiList);
                dataBuilder.append(String.format(java.util.Locale.US, "%.2f", medianRssi));
            } else {
                dataBuilder.append("-100.00");
            }
        }

        String dataLine = dataBuilder.toString();
        Log.d(TAG, "--- Empreinte Enregistrée ---");
        Log.d(TAG, "Header: " + header);
        Log.d(TAG, "Data: " + dataLine);

        if (isPointAlreadyExist(x, y)) {
            runOnUiThread(() -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Point existant")
                        .setMessage("Le point (" + x + ", " + y + ") existe déjà. Voulez-vous remplacer les anciennes données ?")
                        .setPositiveButton("Remplacer", (dialog, which) -> {
                            updateFingerprintInFile(header, dataLine, x, y);
                            loadFingerprintData();
                            loadAndDisplayCsvContent();
                        })
                        .setNegativeButton("Annuler", null)
                        .show();
            });
        } else {
            saveFingerprintToFile(header, dataLine);
            loadFingerprintData();
            loadAndDisplayCsvContent();
        }
    }

    private boolean isPointAlreadyExist(String targetX, String targetY) {
        File file = new File(getExternalFilesDir(null), "fingerprints.csv");
        if (!file.exists()) return false;

        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

            String line = reader.readLine();

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    if (parts[0].equals(targetX) && parts[1].equals(targetY)) {
                        return true; // Found!
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Erreur check existence", e);
        }
        return false;
    }
    private void saveFingerprintToFile(String header, String dataLine) {
        File file = new File(getExternalFilesDir(null), "fingerprints.csv");
        try {
            if (!file.exists() || file.length() == 0) {
                FileOutputStream fos = new FileOutputStream(file);
                OutputStreamWriter writer = new OutputStreamWriter(fos);
                writer.append(header).append("\n");
                writer.append(dataLine).append("\n");
                writer.close();
                fos.close();
            } else {
                FileInputStream fis = new FileInputStream(file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                String existingHeader = reader.readLine();
                reader.close();
                fis.close();

                if (existingHeader != null && existingHeader.equals(header)) {
                    FileOutputStream fos = new FileOutputStream(file, true);
                    OutputStreamWriter writer = new OutputStreamWriter(fos);
                    writer.append(dataLine).append("\n");
                    writer.close();
                    fos.close();
                } else {
                    Log.e(TAG, "File Error: Header not matching with existing header!");
                    Log.e(TAG, "Header file: " + existingHeader);
                    Log.e(TAG, "Header new: " + header);
                    runOnUiThread(() -> Toast.makeText(this, "Erreur de sauvegarde", Toast.LENGTH_LONG).show());
                    //return;
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Erreur lors de l'écriture du fichier", e);
            runOnUiThread(() -> Toast.makeText(this, "Erreur de sauvegarde", Toast.LENGTH_SHORT).show());
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (permissionsGranted()) {
            if (!beaconManager.isBound(this)) {
                beaconManager.bind(this);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (beaconManager.isBound(this)) {
            try {
                beaconManager.stopRangingBeaconsInRegion(beaconRegion);
            } catch (RemoteException e) {
                Log.e(TAG, "Error stopping ranging", e);
            }
            beaconManager.unbind(this);
        }
    }
    private void startCountdownTimer(long durationMs, TextView countdownText, ProgressBar progressBar, Runnable onFinishCallback) {
        if (activeCountdownTimer != null) {
            activeCountdownTimer.cancel();
        }

        progressBar.setVisibility(View.VISIBLE);
        countdownText.setVisibility(View.VISIBLE);

        activeCountdownTimer = new CountDownTimer(durationMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                countdownText.setText(String.format(java.util.Locale.US, "Calcul en cours... %ds",
                        java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) + 1
                ));
            }

            @SuppressLint("SetTextI18n")
            @Override
            public void onFinish() {
                countdownText.setText("Traitement...");

                if (onFinishCallback != null) {
                    onFinishCallback.run();
                }
            }
        }.start();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (beaconManager.isBound(this)) {
            beaconManager.unbind(this);
        }
    }

    private boolean permissionsGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!permissionsGranted()) {
                Toast.makeText(this, "Permissions needed to scan beacons.", Toast.LENGTH_LONG).show();
            }
        }
    }

    class BeaconAdapter extends ArrayAdapter<Beacon> {
        public BeaconAdapter(Context context, ArrayList<Beacon> beacons) {
            super(context, 0, beacons);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            Beacon beacon = getItem(position);

            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_beacon, parent, false);
            }


            // Vérifie si le beacon actuel est dans la liste des beacons sélectionnés
            assert beacon != null;
            if (selectedBeacons.contains(beacon.getBluetoothAddress())) {
                // S'il est sélectionné, on met un fond bleu clair
                convertView.setBackgroundColor(ContextCompat.getColor(getContext(), android.R.color.holo_blue_light));
            } else {
                // Sinon, on remet le fond transparent (ou la couleur par défaut)
                convertView.setBackgroundColor(ContextCompat.getColor(getContext(), android.R.color.transparent));
            }

            TextView beaconIdTextView = convertView.findViewById(R.id.beaconIdTextView);
            TextView beaconRssiTextView = convertView.findViewById(R.id.beaconRssiTextView);

            String beaconId = "Adresse Bluetooth: " + beacon.getBluetoothAddress();
            String rssi = "RSSI: " + beacon.getRssi() + " dBm";

            beaconIdTextView.setText(beaconId);
            beaconRssiTextView.setText(rssi);

            return convertView;
        }
    }
    private void updateFingerprintInFile(String header, String newDataLine, String targetX, String targetY) {
        File file = new File(getExternalFilesDir(null), "fingerprints.csv");
        List<String> lines = new ArrayList<>();
        boolean found = false;
        // Try to read the file
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "Erreur lecture fichier pour update", e);
            return;
        }

        // Find the line to update
        for (int i = 1; i < lines.size(); i++) {
            String currentLine = lines.get(i);
            String[] parts = currentLine.split(",");

            // Check if the line matches the target
            // parts[0] is X, parts[1] is Y
            if (parts.length >= 2 && parts[0].equals(targetX) && parts[1].equals(targetY)) {
                lines.set(i, newDataLine);
                found = true;
                break;
            }
        }

        if (!found) {
            lines.add(newDataLine);
        }

        // Rewrite the file
        try (FileOutputStream fos = new FileOutputStream(file, false);
             OutputStreamWriter writer = new OutputStreamWriter(fos)) {

            for (String line : lines) {
                writer.append(line).append("\n");
            }
            writer.close();
            fos.close();

            runOnUiThread(() -> Toast.makeText(this, "Point (" + targetX + ", " + targetY + ") mis à jour !", Toast.LENGTH_SHORT).show());

        } catch (IOException e) {
            Log.e(TAG, "Erreur écriture fichier update", e);
            runOnUiThread(() -> Toast.makeText(this, "Erreur lors de la mise à jour.", Toast.LENGTH_SHORT).show());
        }
    }
}