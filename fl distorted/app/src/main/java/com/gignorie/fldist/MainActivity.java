package com.gignorie.fldist;

import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.ListView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Реализуем интерфейс для обработки кликов из кастомного адаптера
public class MainActivity extends AppCompatActivity implements CustomExpandableListAdapter.OnChildClickListener {
	
	private static final String TAG = "FLDistRootApp";
	private static final String SONGS_DIR = "/sdcard/Android/data/com.imageline.FLM/files/My Songs/";
	
	// UI элементы
	private ListView songListView;
	private Button scanButton;
	
	// Данные
	private List<String> songNames = new ArrayList<>();
	private Map<String, String> songPathMap = new HashMap<>(); // Название песни -> Полный путь .flm
	private ArrayAdapter<String> songListAdapter;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		// Инициализация UI элементов
		songListView = findViewById(R.id.song_list_view);
		scanButton = findViewById(R.id.scan_button);
		
		// Настройка адаптера для ListView
		songListAdapter = new ArrayAdapter<>(this,
		android.R.layout.simple_list_item_1,
		songNames);
		songListView.setAdapter(songListAdapter);
		
		// Обработчик нажатия на кнопку "Scan"
		scanButton.setOnClickListener(v -> loadSongList());
		
		// Обработчик нажатия на элемент списка (песню)
		songListView.setOnItemClickListener((parent, view, position, id) -> {
			String selectedSongName = songNames.get(position);
			String fullPath = songPathMap.get(selectedSongName);
			
			Toast.makeText(MainActivity.this,
			"Scanning: " + selectedSongName,
			Toast.LENGTH_SHORT).show();
			
			// Запуск сканирования выбранного файла .flm
			scanFileForWavReferences(fullPath);
		});
		
		// Первая попытка загрузки при старте
		loadSongList();
	}
	
	// --- 1. Root Command Execution ---
	
	/**
	* Выполняет команду через 'su' для получения root-доступа.
	*/
	private String executeRootCommand(String command) {
		Process process = null;
		DataOutputStream os = null;
		StringBuilder output = new StringBuilder();
		try {
			process = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(process.getOutputStream());
			
			os.writeBytes(command + "\n");
			os.writeBytes("exit\n");
			os.flush();
			
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append("\n");
			}
			
			BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
			while ((line = errorReader.readLine()) != null) {
				Log.e(TAG, "Root Error Stream: " + line);
			}
			
			process.waitFor();
			if (process.exitValue() != 0) {
				return "ERROR: Command failed with code " + process.exitValue() + "\n" + output.toString();
			}
			
			} catch (Exception e) {
			Log.e(TAG, "Failed to execute root command: " + e.getMessage(), e);
			return "ERROR: Exception executing command: " + e.getMessage();
			} finally {
			try {
				if (os != null) os.close();
				if (process != null) process.destroy();
			} catch (Exception e) { /* Ignored */ }
		}
		return output.toString().trim();
	}
	
	// --- 2. Load Song List ---
	
	private void loadSongList() {
		songNames.clear();
		songPathMap.clear();
		songListAdapter.notifyDataSetChanged();
		
		Toast.makeText(this, "Scanning for .flm files...", Toast.LENGTH_SHORT).show();
		
		String fileListResult = executeRootCommand("find \"" + SONGS_DIR + "\" -name \"*.flm\"");
		
		if (fileListResult.startsWith("ERROR")) {
			Toast.makeText(this, "Root Error or Filesystem access failed.", Toast.LENGTH_LONG).show();
			Log.e(TAG, "Could not get file list (Root Error): " + fileListResult);
			return;
		}
		
		String[] filePaths = fileListResult.split("\n");
		int foundCount = 0;
		
		for (String fullPath : filePaths) {
			if (!fullPath.isEmpty() && fullPath.endsWith(".flm")) {
				File file = new File(fullPath);
				String fileName = file.getName();
				
				String songName = fileName.substring(0, fileName.lastIndexOf(".flm"));
				songNames.add(songName);
				songPathMap.put(songName, fullPath);
				foundCount++;
			}
		}
		
		songListAdapter.notifyDataSetChanged();
		//Toast.makeText(this, "Found " + foundCount + " songs.", Toast.LENGTH_SHORT).show();
	}
	
	// --- 3. Scan and TreeList ---
	
	/**
	* Читает .flm файл и ищет ссылки на WAV-файлы по заданному паттерну.
	*/
	private void scanFileForWavReferences(String fullFlmPath) {
		String fileContent = executeRootCommand("cat \"" + fullFlmPath + "\"");
		
		if (fileContent.startsWith("ERROR")) {
			Toast.makeText(this, "Error reading FLM file content.", Toast.LENGTH_LONG).show();
			Log.e(TAG, "Error reading FLM file: " + fileContent);
			return;
		}
		
		// Внимание: Этот Regex ОЧЕНЬ ненадёжен для бинарных файлов.
		// Ищем: (PTH + цифры + любые символы) + (My Recordings/ + любые символы + .wav)
		Pattern pattern = Pattern.compile("(PTH\\d+.*?)(My Recordings\\/.*?\\.wav)");
		Matcher matcher = pattern.matcher(fileContent);
		
		Map<String, List<String>> treeData = new HashMap<>();
		
		int matchCount = 0;
		
		while (matcher.find()) {
			matchCount++;
			String pthRaw = matcher.group(1);
			String wavPath = matcher.group(2);
			
			String pthKey = "PTH_" + matchCount;
			// Пытаемся извлечь чистый PTH-номер
			try {
				Pattern pthNumPattern = Pattern.compile("(PTH\\d+)");
				Matcher pthNumMatcher = pthNumPattern.matcher(pthRaw);
				if (pthNumMatcher.find()) {
					pthKey = pthNumMatcher.group(1);
				}
				} catch (Exception e) {
				// Остается PTH_matchCount
			}
			
			if (!treeData.containsKey(pthKey)) {
				treeData.put(pthKey, new ArrayList<>());
			}
			treeData.get(pthKey).add(wavPath);
		}
		
		if (matchCount > 0) {
			Toast.makeText(this, "Found " + matchCount + " references.", Toast.LENGTH_SHORT).show();
			displayTreeList(treeData);
			} else {
			Toast.makeText(this, "No references found in file.", Toast.LENGTH_SHORT).show();
		}
	}
	
	/**
	* Отображает найденные ссылки на WAV-файлы в кастомном диалоговом окне с ExpandableListView.
	*/
	private void displayTreeList(Map<String, List<String>> tree) {
		final Dialog dialog = new Dialog(this);
		// Используем макет dialog_tree_list.xml
		dialog.setContentView(R.layout.dialog_tree_list);
		dialog.setTitle("WAV References");
		
		ExpandableListView expandableListView = dialog.findViewById(R.id.tree_list_view);
		
		// Подготовка данных для адаптера
		final List<String> groupList = new ArrayList<>(tree.keySet());
		
		// Создание и установка кастомного адаптера
		CustomExpandableListAdapter adapter = new CustomExpandableListAdapter(
		this,
		groupList,
		tree,
		this // MainActivity выступает в роли обработчика кликов
		);
		expandableListView.setAdapter(adapter);
		
		// Расширяем все группы по умолчанию
		for (int i = 0; i < adapter.getGroupCount(); i++) {
			expandableListView.expandGroup(i);
		}
		
		// Установка размеров диалога (опционально)
		dialog.getWindow().setLayout(
		getResources().getDisplayMetrics().widthPixels * 9/10,
		getResources().getDisplayMetrics().heightPixels * 8/10
		);
		
		dialog.show();
	}
	
	// --- 4. Effect Editor / OnChildClickListener Implementation ---
	
	/**
	* Обрабатывает нажатие на WAV-файл внутри ExpandableListView (вызывается из адаптера).
	*/
	@Override
	public void onWavFileClicked(String wavPath) {
		// Эта функция вызывается из CustomExpandableListAdapter при нажатии на WAV-файл
		openEffectEditor(wavPath);
		// Если хотите закрыть диалог после клика:
		// dialog.dismiss();
	}
	
	// В классе MainActivity
	
	private void openEffectEditor(String wavFilePath) {
		Log.d(TAG, "Action: Opening effect editor for " + wavFilePath);
		
		// Используем Intent для открытия новой Activity и передачи пути к файлу
		Intent intent = new Intent(this, EffectEditorActivity.class);
		intent.putExtra("WAV_PATH", SONGS_DIR + wavFilePath); // Передаем полный путь
		startActivity(intent);
	}
}