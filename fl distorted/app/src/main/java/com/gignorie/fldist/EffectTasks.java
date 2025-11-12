package com.gignorie.fldist;

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import com.gignorie.fldist.WavFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

// Вспомогательный класс для DSP-задач, использующий WavProcessor

public class EffectTasks {

	private static final String TAG = "EffectTasks";

	// =====================================================================
	// 🎧 PreviewTask: Обработка для прослушивания (пишет во временный кеш)
	// =====================================================================
	
	public static class PreviewTask extends AsyncTask<Object, Void, String> {
		private final WeakReference<EffectTaskListener> listenerReference;
		private final int[] currentEffectOrder;
		private final int[] paramLevels;
		private final int[] mixLevels;
		private final boolean isRoot;
		private final WeakReference<Context> contextReference; // Для ContentResolver

		public PreviewTask(EffectTaskListener listener, Context context, int[] effectOrder, int[] paramLevels, int[] mixLevels, boolean isRoot) {
			listenerReference = new WeakReference<>(listener);
			contextReference = new WeakReference<>(context);
			this.currentEffectOrder = effectOrder;
			this.paramLevels = paramLevels;
			this.mixLevels = mixLevels;
			this.isRoot = isRoot;
		}

		@Override
		protected String doInBackground(Object... params) {
			EffectTaskListener listener = listenerReference.get();
			Context context = contextReference.get();
			if (listener == null || context == null) return null;

			Object pathOrUri = params[0];
			File tempFile = null;

			try {
				// 1. Создание временного файла в кеше приложения
				String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
				tempFile = new File(context.getCacheDir(), "temp_preview_" + timestamp + ".wav");

				// 2. ЧТЕНИЕ: Использование Root-команд или SAF
				InputStream inputStream;
				if (isRoot) {
					// ROOT: Копируем файл во временный кеш
					String originalPath = (String) pathOrUri;
					String escapedOriginalPath = originalPath.replace("'", "'\\''");
					String escapedTempPath = tempFile.getAbsolutePath().replace("'", "'\\''");

					String command = "cp -f '" + escapedOriginalPath + "' '" + escapedTempPath + "' && chmod 666 '" + escapedTempPath + "'";
					String result = listener.executeRootCommand(command);
					if (result.startsWith("ERROR")) {
						Log.e(TAG, "Root copy failed for preview: " + result);
						return null;
					}
					// Теперь читаем из временного файла в кеше
					inputStream = context.getContentResolver().openInputStream(Uri.fromFile(tempFile));
				} else {
					// SAF: Читаем напрямую через ContentResolver
					Uri originalUri = (Uri) pathOrUri;
					inputStream = context.getContentResolver().openInputStream(originalUri);
				}

				if (inputStream == null) return null;

				// 3. Загрузка WAV
				WavFile wav = WavFile.openWavStream(inputStream);
				int numFrames = (int) wav.getNumFrames();
				long sampleRate = wav.getSampleRate();
				int numChannels = wav.getNumChannels();
				int validBits = wav.getValidBits();

				double[] buffer = new double[numFrames * numChannels];
				wav.readFrames(buffer, numFrames);
				wav.close();
				inputStream.close();

				// 4. Динамическая DSP-ЦЕПОЧКА (используем WavProcessor)
				for (int effectId : currentEffectOrder) {
					int paramLevel = paramLevels[effectId];
					int mixLevel = mixLevels[effectId];

					double[] dryBuffer = WavProcessor.copyBuffer(buffer);

					WavProcessor.applySingleEffect(
							buffer, dryBuffer, effectId, paramLevel, mixLevel, sampleRate);
				}

				// 5. Сохранение обратно во временный WAV в кеше (для проигрывания)
				try (OutputStream outputStream = context.getContentResolver().openOutputStream(Uri.fromFile(tempFile))) {
					WavFile outWav = WavFile.newWavFile(outputStream, numChannels, buffer.length, validBits, sampleRate);
					outWav.writeFrames(buffer, buffer.length);
					outWav.close();
				}

				return tempFile.getAbsolutePath(); // Возвращаем путь к временному файлу

			} catch (Exception e) {
				Log.e(TAG, "Error in background copy/processing: " + e.getMessage(), e);
				if (tempFile != null && tempFile.exists()) tempFile.delete();
				return null;
			}
		}

		@Override
		protected void onPostExecute(String tempPath) {
			EffectTaskListener listener = listenerReference.get();
			if (listener != null) {
				listener.onPreviewTaskComplete(tempPath);
			}
		}

		@Override
		protected void onCancelled() {
			super.onCancelled();
			EffectTaskListener listener = listenerReference.get();
			if (listener != null) {
				listener.onPreviewTaskComplete(null); // Сообщаем об отмене
			}
		}
	}

	// =====================================================================
	// 🔥 ApplyEffectsTask: Окончательная обработка (перезапись оригинала)
	// =====================================================================
	
	public static class ApplyEffectsTask extends AsyncTask<Object, Void, Boolean> {
		private final WeakReference<EffectTaskListener> listenerReference;
		private final int[] currentEffectOrder;
		private final int[] currentParamLevels;
		private final int[] currentMixLevels;
		private final boolean isRoot;
		private final WeakReference<Context> contextReference;

		public ApplyEffectsTask(EffectTaskListener listener, Context context, int[] effectOrder, int[] paramLevels, int[] mixLevels, boolean isRoot) {
			listenerReference = new WeakReference<>(listener);
			contextReference = new WeakReference<>(context);
			this.currentEffectOrder = effectOrder;
			this.currentParamLevels = paramLevels;
			this.currentMixLevels = mixLevels;
			this.isRoot = isRoot;
		}

		@Override
		protected Boolean doInBackground(Object... params) {
			EffectTaskListener listener = listenerReference.get();
			Context context = contextReference.get();
			if (listener == null || context == null) return false;

			Object pathOrUri = params[0];
			File tempFile = null;

			try {
				// 1. Создание временного файла в кеше
				String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
				tempFile = new File(context.getCacheDir(), "applied_" + timestamp + ".wav");

				// 2. ЧТЕНИЕ
				InputStream inputStream;
				String originalPath = null; // Для Root

				if (isRoot) {
					originalPath = (String) pathOrUri;
					// ROOT: Копируем файл во временный кеш
					String escapedOriginalPath = originalPath.replace("'", "'\\''");
					String escapedTempPath = tempFile.getAbsolutePath().replace("'", "'\\''");

					String copyCommand = "cp -f '" + escapedOriginalPath + "' '" + escapedTempPath + "' && chmod 666 '" + escapedTempPath + "'";
					String result = listener.executeRootCommand(copyCommand);
					if (result.startsWith("ERROR")) {
						Log.e(TAG, "Root copy command failed: " + result);
						return false;
					}
					// Читаем из кеша
					inputStream = context.getContentResolver().openInputStream(Uri.fromFile(tempFile));
				} else {
					// SAF: Читаем напрямую
					Uri originalUri = (Uri) pathOrUri;
					inputStream = context.getContentResolver().openInputStream(originalUri);
				}

				if (inputStream == null) return false;

				// 3. Загрузка WAV
				WavFile wav = WavFile.openWavStream(inputStream);
				int numFrames = (int) wav.getNumFrames();
				long sampleRate = wav.getSampleRate();
				int numChannels = wav.getNumChannels();
				int validBits = wav.getValidBits();

				double[] buffer = new double[numFrames * numChannels];
				wav.readFrames(buffer, numFrames);
				wav.close();
				inputStream.close();

				// 4. Динамическая DSP-ЦЕПОЧКА
				for (int effectId : currentEffectOrder) {
					int paramLevel = currentParamLevels[effectId];
					int mixLevel = currentMixLevels[effectId];

					double[] dryBuffer = WavProcessor.copyBuffer(buffer);

					WavProcessor.applySingleEffect(buffer, dryBuffer, effectId, paramLevel, mixLevel, sampleRate);
				}

				// 5. ПЕРЕЗАПИСЬ ОРИГИНАЛА
				if (isRoot) {
					// ROOT: Сохраняем обработанный WAV обратно во временный файл
					try (OutputStream outputStream = context.getContentResolver().openOutputStream(Uri.fromFile(tempFile))) {
						WavFile outWav = WavFile.newWavFile(outputStream, numChannels, buffer.length, validBits, sampleRate);
						outWav.writeFrames(buffer, buffer.length);
						outWav.close();
					}
					// Перезаписываем оригинал обработанным файлом (ROOT)
					String escapedOriginalPath = originalPath.replace("'", "'\\''");
					String escapedTempPath = tempFile.getAbsolutePath().replace("'", "'\\''");

					String overwriteCommand = "mv -f '" + escapedTempPath + "' '" + escapedOriginalPath + "' && chmod 666 '" + escapedOriginalPath + "'";
					String result = listener.executeRootCommand(overwriteCommand);
					if (result.startsWith("ERROR")) {
						Log.e(TAG, "Root overwrite command failed: " + result);
						return false;
					}
				} else {
					// SAF: Перезаписываем оригинал, открывая OutputStream по URI
					Uri originalUri = (Uri) pathOrUri;
					try (OutputStream outputStream = context.getContentResolver().openOutputStream(originalUri)) {
						if (outputStream == null) throw new IOException("Failed to open output stream for URI.");
						WavFile outWav = WavFile.newWavFile(outputStream, numChannels, buffer.length, validBits, sampleRate);
						outWav.writeFrames(buffer, buffer.length);
						outWav.close();
					}
				}

				return true;

			} catch (Exception e) {
				Log.e(TAG, "Error in final applying/overwriting: " + e.getMessage(), e);
				return false;
			} finally {
				if (tempFile != null && tempFile.exists()) {
					if (!tempFile.delete()) {
						Log.w(TAG, "Failed to delete temporary file: " + tempFile.getName());
					}
				}
			}
		}

		@Override
		protected void onPostExecute(Boolean success) {
			EffectTaskListener listener = listenerReference.get();
			if (listener != null) {
				listener.onApplyTaskComplete(success);
			}
		}

		@Override
		protected void onCancelled() {
			super.onCancelled();
			EffectTaskListener listener = listenerReference.get();
			if (listener != null) {
				listener.onApplyTaskComplete(false); // Отмена = неудача
			}
		}
	}
}
