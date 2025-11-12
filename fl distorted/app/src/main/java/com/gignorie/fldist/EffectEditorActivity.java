package com.gignorie.fldist;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.media.MediaPlayer;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.stream.Collectors;

// Теперь EffectEditorActivity реализует EffectTaskListener
public class EffectEditorActivity extends AppCompatActivity implements EffectTaskListener {
	
	private static final String TAG = "EffectEditorActivity";
	
	// --- ФАЙЛОВЫЕ ПЕРЕМЕННЫЕ ---
	private String rootWavPath;
	private Uri safWavUri;
	private boolean isRootAccess = false;
	
	// --- КОНСТАНТЫ ID ЭФФЕКТОВ (Индексы массивов) ---
	// Используем константы из WavProcessor для совместимости
	public static final int FX_LPF_CUTOFF = WavProcessor.FX_LPF_CUTOFF;
	public static final int FX_RING_MOD = WavProcessor.FX_RING_MOD;
	public static final int FX_CLIP_DECAY = WavProcessor.FX_CLIP_DECAY;
	public static final int FX_REAL_BITCRUSH = WavProcessor.FX_REAL_BITCRUSH;
	public static final int FX_REAL_DRIVE = WavProcessor.FX_REAL_DRIVE;
	public static final int FX_REAL_SATURATION = WavProcessor.FX_REAL_SATURATION;
	public static final int NUM_EFFECTS = 6;
	
	// --- ГЛОБАЛЬНЫЕ МАССИВЫ ---
	public final int[] paramLevels = new int[NUM_EFFECTS];
	public final int[] mixLevels = new int[NUM_EFFECTS];
	
	// --- МАССИВ ПОРЯДКА НАНЕСЕНИЯ ЭФФЕКТОВ (Цепочка DSP) ---
	private int[] effectOrder = {
		FX_LPF_CUTOFF, FX_RING_MOD, FX_CLIP_DECAY,
		FX_REAL_BITCRUSH, FX_REAL_DRIVE, FX_REAL_SATURATION
	};
	
	// UI-элементы
	private Button previewButton;
	private Button savePresetButton;
	private Button applyEffectsButton;
	private EffectAdapter effectAdapter;
	
	// Медиаплеер и управление состоянием
	private MediaPlayer mediaPlayer;
	private boolean isPlaying = false;
	private String tempAudioPath = null;
	// Теперь используем вынесенные классы
	private EffectTasks.PreviewTask currentPreviewTask;
	private EffectTasks.ApplyEffectsTask currentApplyTask;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_effect_editor);
		
		// 1. ОПРЕДЕЛЕНИЕ ТИПА ДОСТУПА (ROOT или SAF)
		String fullWavPath = getIntent().getStringExtra("WAV_PATH");
		String relativeWavPath = getIntent().getStringExtra("RELATIVE_WAV_PATH");
		// !!! ИЗМЕНЕНО: Получаем ROOT_DIR_URI, который указывает на FL Studio Mobile/ !!!
		String rootDirUriStr = getIntent().getStringExtra("ROOT_DIR_URI");
		
		if (fullWavPath != null) {
			isRootAccess = true;
			// Используем fullWavPath напрямую, если там уже корректный путь
			rootWavPath = fullWavPath;
			Log.d(TAG, "Root mode active. Path: " + rootWavPath);
			
			// !!! ИСПРАВЛЕНА ЛОГИКА SAF !!!
			} else if (relativeWavPath != null && rootDirUriStr != null) {
			isRootAccess = false;
			try {
				// Парсим URI корневой папки FL Studio Mobile
				Uri rootDirUri = Uri.parse(rootDirUriStr);
				DocumentFile pickedDir = DocumentFile.fromTreeUri(this, rootDirUri);
				
				if (pickedDir != null) {
					// Ищем файл, используя вспомогательный метод (нужен в Activity)
					// relativeWavPath = "My Recordings/Kick.wav"
					DocumentFile wavFile = findDocumentFile(pickedDir, relativeWavPath);
					if (wavFile != null) {
						safWavUri = wavFile.getUri();
						} else {
						Log.e(TAG, "WAV file not found relative to root URI: " + relativeWavPath);
					}
				}
				} catch (Exception e) {
				Log.e(TAG, "SAF URI parsing failed: " + e.getMessage());
			}
			Log.d(TAG, "SAF mode active. URI: " + safWavUri);
		}
		
		// 2. Инициализация UI и Проверка Доступа
		TextView pathTextView = findViewById(R.id.effect_path_text);
		previewButton = findViewById(R.id.button_preview);
		savePresetButton = findViewById(R.id.button_save_settings);
		applyEffectsButton = findViewById(R.id.button_apply_effects);
		
		String displayPath = (isRootAccess && rootWavPath != null)
		? getFileName(rootWavPath)
		: (safWavUri != null ? getFileName(safWavUri.toString()) : null);
		
		if (displayPath != null) {
			pathTextView.setText("Editing: " + displayPath);
			} else {
			pathTextView.setText("Error: File not found or inaccessible.");
			previewButton.setEnabled(false);
			applyEffectsButton.setEnabled(false);
		}
		
		// 3. Инициализация массивов и загрузка пресета
		mixLevels[FX_LPF_CUTOFF] = 100;
		mixLevels[FX_RING_MOD] = 100;
		mixLevels[FX_CLIP_DECAY] = 100;
		loadEffectPreset();
		
		// 4. Настройка RecyclerView
		setupRecyclerView();
		
		previewButton.setOnClickListener(v -> togglePreview());
		savePresetButton.setOnClickListener(v -> saveEffectPreset());
		applyEffectsButton.setOnClickListener(v -> applyEffectsAndOverwrite());
		
		updatePreviewButtonText();
		mediaPlayer = new MediaPlayer();
	}
	
	// -------------------------------------------------------------------------
	// Реализация EffectTaskListener
	// -------------------------------------------------------------------------
	
	/**
	* Вызывается PreviewTask после завершения обработки.
	*/
	@Override
	public void onPreviewTaskComplete(String tempPath) {
		currentPreviewTask = null;
		if (tempPath != null) {
			startPlayback(tempPath);
			} else {
			stopPlayback();
			Toast.makeText(this, "File processing failed. Check access/WavFile.", Toast.LENGTH_LONG).show();
		}
		previewButton.setEnabled(true);
	}
	
	/**
	* Вызывается ApplyEffectsTask после завершения перезаписи.
	*/
	@Override
	public void onApplyTaskComplete(Boolean success) {
		currentApplyTask = null;
		applyEffectsButton.setEnabled(true);
		savePresetButton.setEnabled(true);
		applyEffectsButton.setText("🔥 APPLY EFFECTS AND OVERWRITE FILE");
		
		if (success) {
			Toast.makeText(this, "Effects applied successfully! File overwritten.", Toast.LENGTH_LONG).show();
			} else {
			Toast.makeText(this, "Failed to apply effects and overwrite file. Check permissions/Root.", Toast.LENGTH_LONG).show();
		}
	}
	
	/**
	* Передает команду на выполнение в режиме Root (только если isRootAccess = true).
	*/
	@Override
	public String executeRootCommand(String command) {
		if (!isRootAccess) return "ERROR: Root access not enabled.";
		
		Process process = null;
		DataOutputStream os = null;
		StringBuilder output = new StringBuilder();
		try {
			process = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(process.getOutputStream());
			
			os.writeBytes(command + "\n");
			os.writeBytes("exit\n");
			os.flush();
			
			// ... (остальная логика чтения потоков) ...
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append("\n");
			}
			
			BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
			while ((line = errorReader.readLine()) != null) {
				Log.e(TAG, "Root Error Stream: " + line + "\n ");
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
	
	// -------------------------------------------------------------------------
	// Вспомогательные методы
	// -------------------------------------------------------------------------
	
	/**
	* Рекурсивно ищет DocumentFile (нужно для SAF в onCreate).
	*/
	private DocumentFile findDocumentFile(DocumentFile root, String wavPath) {
		// wavPath = "My Recordings/Kick.wav"
		String[] parts = wavPath.split("/");
		DocumentFile current = root;
		
		for (String part : parts) {
			if (current == null) return null;
			// Ищем дочерний файл/папку по имени
			DocumentFile next = current.findFile(part);
			if (next != null) {
				current = next;
				} else {
				return null;
			}
		}
		return current;
	}
	
	private String getFileName(String fullPathOrUri) {
		if (fullPathOrUri == null) return "N/A";
		int lastSlash = fullPathOrUri.lastIndexOf('/');
		return lastSlash >= 0 ? fullPathOrUri.substring(lastSlash + 1) : fullPathOrUri;
	}
	
	// -------------------------------------------------------------------------
	// UI и Жизненный Цикл
	// -------------------------------------------------------------------------
	
	private void setupRecyclerView() {
		RecyclerView recyclerView = findViewById(R.id.effect_chain_recyclerview);
		effectAdapter = new EffectAdapter(this, effectOrder, paramLevels, mixLevels);
		recyclerView.setAdapter(effectAdapter);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		
		ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
		ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
			@Override
			public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
				effectAdapter.onItemMove(viewHolder.getAdapterPosition(), target.getAdapterPosition());
				return true;
			}
			@Override
			public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}
			@Override
			public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
				super.onSelectedChanged(viewHolder, actionState);
				if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) viewHolder.itemView.setAlpha(0.7f);
			}
			@Override
			public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
				super.clearView(recyclerView, viewHolder);
				if (viewHolder != null) viewHolder.itemView.setAlpha(1.0f);
			}
		};
		
		ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
		touchHelper.attachToRecyclerView(recyclerView);
	}
	
	public void updateEffectOrder(int[] newOrder) {
		if (newOrder.length != NUM_EFFECTS) {
			Log.e(TAG, "New effect order length mismatch.");
			return;
		}
		this.effectOrder = newOrder;
		Log.d(TAG, "Effect order updated: " + Arrays.toString(newOrder));
		updatePreviewButtonText();
	}
	
	private void updatePreviewButtonText() {
		if (previewButton != null) {
			StringBuilder sb = new StringBuilder();
			for (int id : effectOrder) {
				sb.append(id).append("-");
			}
			if (sb.length() > 0) sb.setLength(sb.length() - 1);
			
			previewButton.setText(isPlaying ? "⏹️ STOP Preview" : "🎧 PREVIEW (Chain: " + sb.toString() + ")");
			previewButton.setEnabled(true);
		}
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		stopPlayback();
		if (currentPreviewTask != null) currentPreviewTask.cancel(true);
		if (currentApplyTask != null) currentApplyTask.cancel(true);
		if (mediaPlayer != null) {
			mediaPlayer.release();
			mediaPlayer = null;
		}
		if (tempAudioPath != null) {
			File tempFile = new File(tempAudioPath);
			if (tempFile.exists()) tempFile.delete();
			tempAudioPath = null;
		}
	}
	
	// -------------------------------------------------------------------------
	// Воспроизведение и DSP-Запуск
	// -------------------------------------------------------------------------
	
	private void togglePreview() {
		if (rootWavPath == null && safWavUri == null) {
			Toast.makeText(this, "File is not accessible.", Toast.LENGTH_SHORT).show();
			return;
		}
		
		if (isPlaying) {
			stopPlayback();
			} else {
			if (currentPreviewTask != null && currentPreviewTask.getStatus() == AsyncTask.Status.RUNNING) {
				currentPreviewTask.cancel(true);
			}
			
			Object pathOrUri = isRootAccess ? rootWavPath : safWavUri;
			
			// Запускаем вынесенный PreviewTask
			currentPreviewTask = new EffectTasks.PreviewTask(
			this, this, Arrays.copyOf(effectOrder, effectOrder.length),
			paramLevels, mixLevels, isRootAccess
			);
			currentPreviewTask.execute(pathOrUri);
			previewButton.setText("🔄 Processing...");
			previewButton.setEnabled(false);
		}
	}
	
	private void startPlayback(String path) {
		if (path == null) {
			Toast.makeText(this, "Failed to process file.", Toast.LENGTH_LONG).show();
			updatePreviewButtonText();
			return;
		}
		
		tempAudioPath = path;
		
		try {
			mediaPlayer.reset();
			mediaPlayer.setDataSource(tempAudioPath);
			mediaPlayer.prepare();
			mediaPlayer.start();
			
			isPlaying = true;
			updatePreviewButtonText();
			mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
			Toast.makeText(this, "Playback started.", Toast.LENGTH_SHORT).show();
			
			} catch (IOException e) {
			Log.e(TAG, "Error setting up playback: " + e.getMessage(), e);
			Toast.makeText(this, "Playback setup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
			stopPlayback();
		}
	}
	
	private void stopPlayback() {
		if (mediaPlayer != null && mediaPlayer.isPlaying()) {
			mediaPlayer.stop();
		}
		isPlaying = false;
		updatePreviewButtonText();
	}
	
	private void applyEffectsAndOverwrite() {
		if (rootWavPath == null && safWavUri == null) {
			Toast.makeText(this, "Error: No file selected for applying effects.", Toast.LENGTH_LONG).show();
			return;
		}
		
		stopPlayback();
		if (currentPreviewTask != null && currentPreviewTask.getStatus() == AsyncTask.Status.RUNNING) currentPreviewTask.cancel(true);
		if (currentApplyTask != null && currentApplyTask.getStatus() == AsyncTask.Status.RUNNING) {
			Toast.makeText(this, "Processing is already running.", Toast.LENGTH_SHORT).show();
			return;
		}
		
		Object pathOrUri = isRootAccess ? rootWavPath : safWavUri;
		
		new AlertDialog.Builder(this)
		.setTitle("Apply and Overwrite")
		.setMessage("This will permanently overwrite the original file:\n" + getFileName(pathOrUri.toString()) + "\nAre you sure?")
		.setPositiveButton("YES", (dialog, which) -> {
			
			int[] effectOrderCopy = Arrays.copyOf(effectOrder, effectOrder.length);
			int[] paramLevelsCopy = Arrays.copyOf(paramLevels, paramLevels.length);
			int[] mixLevelsCopy = Arrays.copyOf(mixLevels, mixLevels.length);
			
			// Запускаем вынесенный ApplyEffectsTask
			currentApplyTask = new EffectTasks.ApplyEffectsTask(
			this, this, effectOrderCopy, paramLevelsCopy, mixLevelsCopy, isRootAccess
			);
			currentApplyTask.execute(pathOrUri);
			
			applyEffectsButton.setText("🔄 APPLYING...");
			applyEffectsButton.setEnabled(false);
			savePresetButton.setEnabled(false);
		})
		.setNegativeButton("NO", null)
		.show();
	}
	
	// -------------------------------------------------------------------------
	// Пресеты
	// -------------------------------------------------------------------------
	
	private void saveEffectPreset() {
		String presetName = "DefaultChainPreset";
		
		String orderStr = Arrays.stream(effectOrder).mapToObj(String::valueOf).collect(Collectors.joining(","));
		String paramStr = Arrays.stream(paramLevels).mapToObj(String::valueOf).collect(Collectors.joining(","));
		String mixStr = Arrays.stream(mixLevels).mapToObj(String::valueOf).collect(Collectors.joining(","));
		
		getSharedPreferences("EffectPresets", MODE_PRIVATE)
		.edit()
		.putString(presetName + "_ORDER", orderStr)
		.putString(presetName + "_PARAM", paramStr)
		.putString(presetName + "_MIX", mixStr)
		.apply();
		
		Toast.makeText(this, "Preset '" + presetName + "' saved successfully!", Toast.LENGTH_SHORT).show();
	}
	
	private void loadEffectPreset() {
		String presetName = "DefaultChainPreset";
		SharedPreferences prefs = getSharedPreferences("EffectPresets", MODE_PRIVATE);
		
		String orderStr = prefs.getString(presetName + "_ORDER", null);
		String paramStr = prefs.getString(presetName + "_PARAM", null);
		String mixStr = prefs.getString(presetName + "_MIX", null);
		
		if (orderStr != null && paramStr != null && mixStr != null) {
			try {
				String[] orderParts = orderStr.split(",");
				if (orderParts.length != NUM_EFFECTS) return;
				
				for (int i = 0; i < NUM_EFFECTS; i++) {
					effectOrder[i] = Integer.parseInt(orderParts[i]);
				}
				
				String[] paramParts = paramStr.split(",");
				String[] mixParts = mixStr.split(",");
				
				if (paramParts.length == NUM_EFFECTS && mixParts.length == NUM_EFFECTS) {
					for (int i = 0; i < NUM_EFFECTS; i++) {
						paramLevels[i] = Integer.parseInt(paramParts[i]);
						mixLevels[i] = Integer.parseInt(mixParts[i]);
					}
					
					if (effectAdapter != null) {
						effectAdapter.updateAdapterOrder(effectOrder, paramLevels, mixLevels);
					}
					
					Log.d(TAG, "Preset loaded.");
					Toast.makeText(this, "Preset loaded.", Toast.LENGTH_SHORT).show();
					updatePreviewButtonText();
				}
				} catch (NumberFormatException e) {
				Log.e(TAG, "Error parsing preset data: " + e.getMessage());
			}
			} else {
			Log.d(TAG, "No preset found, using default settings.");
		}
	}
}