package com.gignorie.fldist;

import android.content.Intent;
import android.net.Uri;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile; // Для работы с SAF
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors; // Для удобной работы с сортировкой

public class MainActivity extends AppCompatActivity implements CustomExpandableListAdapter.OnChildClickListener {
	
	private static final String TAG = "FLDistRootApp";
	private static final String SONGS_DIR_DEFAULT = "/sdcard/Android/data/com.imageline.FLM/files/My Songs/";
	private static final int REQUEST_SAF_FOLDER = 42;
	
	private String songsDirPath = SONGS_DIR_DEFAULT;
	private boolean hasRootAccess = false;
	
	// --- Сортировка ---
	private enum SortMode {
		DATE_NEWEST,
		NAME_A_Z,
		NAME_Z_A
	}
	private SortMode currentSortMode = SortMode.DATE_NEWEST;
	
	// UI элементы
	private ListView songListView;
	private Button scanButton;
	private Button sortButton; // НОВАЯ КНОПКА
	
	// --- Хранение данных ---
	// Вместо списка строк, используем список объектов для хранения метаданных
	private List<SongItem> songItems = new ArrayList<>();
	
	// Название песни -> Полный путь .flm (Root) ИЛИ URI .flm (SAF)
	private Map<String, String> songPathMap = new HashMap<>();
	private ArrayAdapter<String> songListAdapter;
	
	// Приватный класс для хранения данных о песне
	private static class SongItem {
		String name;
		String pathOrUri;
		long lastModified; // В миллисекундах
		
		public SongItem(String name, String pathOrUri, long lastModified) {
			this.name = name;
			this.pathOrUri = pathOrUri;
			this.lastModified = lastModified;
		}
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		// Инициализация UI элементов
		songListView = findViewById(R.id.song_list_view);
		scanButton = findViewById(R.id.scan_button);
		// Предполагаем, что в R.layout.activity_main добавлена кнопка с id button_sort
		sortButton = findViewById(R.id.button_sort);
		
		// Настройка адаптера для ListView (покажем только имена)
		songListAdapter = new ArrayAdapter<>(this,
		android.R.layout.simple_list_item_1,
		new ArrayList<>()); // Инициализируем пустым списком
		songListView.setAdapter(songListAdapter);
		
		// 1. Проверяем root при старте
		hasRootAccess = checkRootAccess();
		
		// 2. Если root нет, запрашиваем выбор директории
		if (!hasRootAccess) {
			Toast.makeText(this, "Root access denied. Please select the 'FL Studio Mobile' folder.", Toast.LENGTH_LONG).show();
			openDirectoryChooser();
		}
		
		// Обработчик нажатия на кнопку "Scan"
		scanButton.setOnClickListener(v -> loadSongList());
		
		// НОВЫЙ Обработчик нажатия на кнопку "Sort"
		sortButton.setOnClickListener(v -> toggleSortMode());
		updateSortButtonText(); // Обновляем текст кнопки при запуске
		
		// Обработчик нажатия на элемент списка (песню)
		songListView.setOnItemClickListener((parent, view, position, id) -> {
			String selectedSongName = (String) songListAdapter.getItem(position);
			
			// Находим SongItem по имени (нужно для получения URI/Path)
			SongItem selectedItem = songItems.stream()
			.filter(item -> item.name.equals(selectedSongName))
			.findFirst()
			.orElse(null);
			
			if (selectedItem != null) {
				Toast.makeText(MainActivity.this,
				"Scanning: " + selectedSongName,
				Toast.LENGTH_SHORT).show();
				
				// Запуск сканирования выбранного файла .flm
				scanFileForWavReferences(selectedItem.pathOrUri);
			}
		});
		
		// Первая попытка загрузки при старте
		loadSongList();
	}
	
	/**
	* Переключает режим сортировки и перезагружает список.
	*/
	private void toggleSortMode() {
		switch (currentSortMode) {
			case DATE_NEWEST:
			currentSortMode = SortMode.NAME_A_Z;
			break;
			case NAME_A_Z:
			currentSortMode = SortMode.NAME_Z_A;
			break;
			case NAME_Z_A:
			currentSortMode = SortMode.DATE_NEWEST;
			break;
		}
		updateSortButtonText();
		sortAndDisplaySongs();
	}
	
	/**
	* Обновляет текст на кнопке сортировки.
	*/
	private void updateSortButtonText() {
		String text;
		switch (currentSortMode) {
			case DATE_NEWEST:
			text = "Сортировка: Новые (↓)";
			break;
			case NAME_A_Z:
			text = "Сортировка: Имя (A-Z)";
			break;
			case NAME_Z_A:
			text = "Сортировка: Имя (Z-A)";
			break;
			default:
			text = "Сортировка";
		}
		if (sortButton != null) {
			sortButton.setText(text);
		}
	}
	
	// --- 1. Root/SAF Helpers ---
	
	private boolean checkRootAccess() {
		try {
			Process p = Runtime.getRuntime().exec("su");
			DataOutputStream os = new DataOutputStream(p.getOutputStream());
			os.writeBytes("exit\n");
			os.flush();
			int exitValue = p.waitFor();
			return (exitValue == 0);
			} catch (Exception e) {
			return false;
		}
	}
	
	private void openDirectoryChooser() {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		startActivityForResult(intent, REQUEST_SAF_FOLDER);
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		
		if (requestCode == REQUEST_SAF_FOLDER && resultCode == RESULT_OK && data != null) {
			Uri treeUri = data.getData();
			
			if (treeUri != null) {
				getContentResolver().takePersistableUriPermission(treeUri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				
				songsDirPath = treeUri.toString();
				
				Toast.makeText(this, "Selected FL Studio Mobile root: " + songsDirPath, Toast.LENGTH_LONG).show();
				
				loadSongList();
			}
			} else if (!hasRootAccess) {
			Toast.makeText(this, "Directory selection is required without root access.", Toast.LENGTH_LONG).show();
		}
	}
	
	private String executeRootCommand_RootOnly(String command) {
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
		songItems.clear();
		songPathMap.clear();
		
		Toast.makeText(this, "Scanning for .flm files...", Toast.LENGTH_SHORT).show();
		
		int foundCount = 0;
		
		if (hasRootAccess) {
			// --- Логика для Root ---
			// Используем команду find, чтобы получить путь и время модификации
			// -printf "%T@ %p\n" выводит время модификации (секунды с начала эпохи) и путь
			String command = "find \"" + SONGS_DIR_DEFAULT + "\" -maxdepth 1 -name \"*.flm\" -printf \"%T@ %p\\n\"";
			String fileListResult = executeRootCommand_RootOnly(command);
			
			if (fileListResult.startsWith("ERROR")) {
				Toast.makeText(this, "Root Error or Filesystem access failed.", Toast.LENGTH_LONG).show();
				Log.e(TAG, "Could not get file list (Root Error): " + fileListResult);
				return;
			}
			
			String[] fileEntries = fileListResult.split("\n");
			
			for (String entry : fileEntries) {
				if (entry.isEmpty()) continue;
				try {
					// Формат: "1731422799.000000000 /sdcard/.../My Songs/Song.flm"
					int spaceIndex = entry.indexOf(' ');
					if (spaceIndex == -1) continue;
					
					// Время в секундах
					String timeStr = entry.substring(0, spaceIndex);
					long lastModifiedSec = (long) Double.parseDouble(timeStr);
					long lastModifiedMs = lastModifiedSec * 1000;
					
					String fullPath = entry.substring(spaceIndex + 1).trim();
					File file = new File(fullPath);
					String fileName = file.getName();
					
					String songName = fileName.substring(0, fileName.lastIndexOf(".flm"));
					
					songItems.add(new SongItem(songName, fullPath, lastModifiedMs));
					songPathMap.put(songName, fullPath);
					foundCount++;
					} catch (Exception e) {
					Log.e(TAG, "Error parsing root file entry: " + entry, e);
				}
			}
			
			} else if (songsDirPath != SONGS_DIR_DEFAULT) {
			// --- Логика для SAF ---
			Uri rootTreeUri = Uri.parse(songsDirPath);
			DocumentFile rootDir = DocumentFile.fromTreeUri(this, rootTreeUri);
			
			if (rootDir != null && rootDir.isDirectory()) {
				DocumentFile mySongsDir = rootDir.findFile("My Songs");
				
				if (mySongsDir != null && mySongsDir.isDirectory()) {
					for (DocumentFile file : mySongsDir.listFiles()) {
						if (file.getName() != null && file.getName().toLowerCase().endsWith(".flm")) {
							String fileName = file.getName();
							String songName = fileName.substring(0, fileName.lastIndexOf(".flm"));
							
							// Используем lastModified() из DocumentFile
							songItems.add(new SongItem(songName, file.getUri().toString(), file.lastModified()));
							songPathMap.put(songName, file.getUri().toString());
							foundCount++;
						}
					}
					} else {
					Toast.makeText(this, "Could not find 'My Songs' folder inside selected root.", Toast.LENGTH_LONG).show();
				}
				} else {
				Toast.makeText(this, "Selected folder is not valid or accessible.", Toast.LENGTH_LONG).show();
			}
		}
		
		Toast.makeText(this, "Found " + foundCount + " songs.", Toast.LENGTH_SHORT).show();
		sortAndDisplaySongs();
	}
	
	/**
	* Сортирует список песен и обновляет ListView.
	*/
	private void sortAndDisplaySongs() {
		// 1. Применяем сортировку
		// ... (в методе sortAndDisplaySongs)
		
		// 1. Применяем сортировку
		// !!! ИСПРАВЛЕНО: Явно указываем тип (SongItem) для лямбда-выражения !!!
		Comparator<SongItem> comparator;
		
		switch (currentSortMode) {
			case DATE_NEWEST:
			// Сортировка по дате (обратный порядок: новейшие вверху)
			// Явно указываем (SongItem item) -> ...
			comparator = Comparator.comparingLong((SongItem item) -> item.lastModified).reversed();
			break;
			case NAME_A_Z:
			// Сортировка по имени (A-Z)
			// Явно указываем (SongItem item) -> ...
			comparator = Comparator.comparing((SongItem item) -> item.name, String.CASE_INSENSITIVE_ORDER);
			break;
			case NAME_Z_A:
			// Сортировка по имени (Z-A, обратный A-Z)
			// Явно указываем (SongItem item) -> ...
			comparator = Comparator.comparing((SongItem item) -> item.name, String.CASE_INSENSITIVE_ORDER).reversed();
			break;
			default:
			// Сортировка по умолчанию
			comparator = Comparator.comparing((SongItem item) -> item.name);
			break;
		}
		
		Collections.sort(songItems, comparator);
		
		// 2. Обновляем адаптер ListView только именами
		songListAdapter.clear();
		for (SongItem item : songItems) {
			songListAdapter.add(item.name);
		}
		songListAdapter.notifyDataSetChanged();
	}
	
	// --- 3. Scan and TreeList ---
	
	private void scanFileForWavReferences(String fullPathOrUri) {
		// ... (Логика сканирования FLM, не изменена) ...
		String fileContent = null;
		
		if (hasRootAccess) {
			fileContent = executeRootCommand_RootOnly("cat \"" + fullPathOrUri + "\"");
			
			if (fileContent.startsWith("ERROR")) {
				Toast.makeText(this, "Error reading FLM file content (Root).", Toast.LENGTH_LONG).show();
				Log.e(TAG, "Error reading FLM file: " + fileContent);
				return;
			}
			
			} else {
			try {
				Uri fileUri = Uri.parse(fullPathOrUri);
				try (InputStream is = getContentResolver().openInputStream(fileUri);
				BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
					
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = reader.readLine()) != null) {
						sb.append(line).append("\n");
					}
					fileContent = sb.toString();
					
					} catch (Exception e) {
					throw new Exception(e);
				}
				
				} catch (Exception e) {
				Toast.makeText(this, "Error reading FLM file content (SAF).", Toast.LENGTH_LONG).show();
				Log.e(TAG, "Error reading FLM file (SAF): " + e.getMessage(), e);
				return;
			}
		}
		
		Pattern pattern = Pattern.compile("(PTH\\d+.*?)(My Recordings\\/.*?\\.wav)");
		Matcher matcher = pattern.matcher(fileContent);
		
		Map<String, List<String>> treeData = new HashMap<>();
		
		int matchCount = 0;
		
		while (matcher.find()) {
			matchCount++;
			String pthRaw = matcher.group(1);
			String wavPath = matcher.group(2);
			
			String pthKey = "PTH_" + matchCount;
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
	
	private void displayTreeList(Map<String, List<String>> tree) {
		final Dialog dialog = new Dialog(this);
		dialog.setContentView(R.layout.dialog_tree_list);
		dialog.setTitle("WAV References");
		
		ExpandableListView expandableListView = dialog.findViewById(R.id.tree_list_view);
		
		final List<String> groupList = new ArrayList<>(tree.keySet());
		
		CustomExpandableListAdapter adapter = new CustomExpandableListAdapter(
		this,
		groupList,
		tree,
		this
		);
		expandableListView.setAdapter(adapter);
		
		for (int i = 0; i < adapter.getGroupCount(); i++) {
			expandableListView.expandGroup(i);
		}
		
		dialog.getWindow().setLayout(
		getResources().getDisplayMetrics().widthPixels * 9/10,
		getResources().getDisplayMetrics().heightPixels * 8/10
		);
		
		dialog.show();
	}
	
	// --- 4. Effect Editor / OnChildClickListener Implementation ---
	
	@Override
	public void onWavFileClicked(String wavPath) {
		openEffectEditor(wavPath);
	}
	
	private void openEffectEditor(String wavFilePath) {
		Log.d(TAG, "Action: Opening effect editor for " + wavFilePath);
		
		Intent intent = new Intent(this, EffectEditorActivity.class);
		
		if (hasRootAccess) {
			File mySongsFile = new File(SONGS_DIR_DEFAULT);
			String rootDirRootOnly = mySongsFile.getParent() + "/";
			
			intent.putExtra("WAV_PATH", rootDirRootOnly + wavFilePath);
			Log.d(TAG, "Root Path: " + rootDirRootOnly + wavFilePath);
			
			} else {
			intent.putExtra("RELATIVE_WAV_PATH", wavFilePath);
			intent.putExtra("ROOT_DIR_URI", songsDirPath);
			Log.d(TAG, "SAF Path: " + songsDirPath + " + " + wavFilePath);
		}
		
		startActivity(intent);
	}
}