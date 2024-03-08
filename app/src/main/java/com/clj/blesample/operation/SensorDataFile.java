package com.clj.blesample.operation;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import android.content.Context;

public class SensorDataFile {
    File sensorDataFile;
    String fileName = "emg.data";
    SensorDataFile() {
        sensorDataFile = new File("/storage/self/primary/FIRST", fileName);
    }
    public void readFile() throws IOException{
        FileInputStream fis = new FileInputStream("/storage/self/primary/FIRST/emg.data");
        InputStreamReader inputStreamReader =
                new InputStreamReader(fis, StandardCharsets.UTF_8);
        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(inputStreamReader)) {
            String line = reader.readLine();
            while (line != null) {
                stringBuilder.append(line).append('\n');
                line = reader.readLine();
            }
        } catch (IOException e) {
            // Error occurred when opening raw file for reading.
        } finally {
            String contents = stringBuilder.toString();
        }
    }
    public void writeToFile() throws IOException{
        sensorDataFile = new File("/storage/self/primary/FIRST", fileName);

        String fileContents = "Hello world!";

        try (FileOutputStream fout = new FileOutputStream(sensorDataFile, true)) {
            fout.write(fileContents.getBytes());
            fout.close();
        }
    }
}
