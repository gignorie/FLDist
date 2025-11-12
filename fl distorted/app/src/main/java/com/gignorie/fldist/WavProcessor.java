package com.gignorie.fldist;

// Вспомогательный класс для DSP-логики, чтобы разгрузить Activity

public final class WavProcessor {

	// --- КОНСТАНТЫ ID ЭФФЕКТОВ ---
	public static final int FX_LPF_CUTOFF = 0;
	public static final int FX_RING_MOD = 1;
	public static final int FX_CLIP_DECAY = 2;
	public static final int FX_REAL_BITCRUSH = 3;
	public static final int FX_REAL_DRIVE = 4;
	public static final int FX_REAL_SATURATION = 5;

	private WavProcessor() {
		// Приватный конструктор для статического класса
	}

	/**
	 * Смешивает обработанный буфер (wet) с оригинальным (dry) на основе уровня микса.
	 * @param original Оригинальный буфер (Dry)
	 * @param processed Обработанный буфер (Wet, который будет изменен)
	 * @param mixLevel Уровень Wet-сигнала (0-100)
	 */
	public static void mixSignal(double[] original, double[] processed, int mixLevel) {
		if (mixLevel >= 100) return;

		double wet = mixLevel / 100.0;
		double dry = 1.0 - wet;

		for (int i = 0; i < original.length; i++) {
			processed[i] = (processed[i] * wet) + (original[i] * dry);
		}
	}

	/**
	 * Создает копию буфера.
	 */
	public static double[] copyBuffer(double[] source) {
		double[] destination = new double[source.length];
		System.arraycopy(source, 0, destination, 0, source.length);
		return destination;
	}

	/**
	 * Универсальный метод для применения одного DSP-эффекта.
	 */
	public static void applySingleEffect(double[] buffer, double[] dryBuffer,
										 int effectId, int paramLevel, int mixLevel,
										 long sampleRate) {

		if (mixLevel <= 0) return;

		double[] wetBuffer = copyBuffer(buffer);

		// --- Логика DSP-эффектов ---
		switch (effectId) {
			case FX_LPF_CUTOFF:
				// 1. Low-Pass Filter (IIR)
				double minCutoff = 100.0; double maxCutoff = 3000.0;
				double cutoffFreq = minCutoff + (maxCutoff - minCutoff) * (paramLevel / 100.0);
				double RC = 1.0 / (cutoffFreq * 2.0 * Math.PI);
				double alpha = 1.0 / (RC * sampleRate + 1.0);
				double lastOutput = 0.0;
				for (int i = 0; i < wetBuffer.length; i++) {
					lastOutput = alpha * wetBuffer[i] + (1.0 - alpha) * lastOutput;
					wetBuffer[i] = lastOutput;
				}
				break;

			case FX_RING_MOD:
				// 2. Ring Modulation
				double minModFreq = 50.0; double maxModFreq = 500.0;
				double modFreq = minModFreq + (maxModFreq - minModFreq) * (paramLevel / 100.0);
				double modPhase = 0.0;
				double modIncrement = 2.0 * Math.PI * modFreq / sampleRate;
				for (int i = 0; i < wetBuffer.length; i++) {
					wetBuffer[i] *= Math.sin(modPhase);
					modPhase += modIncrement;
					if (modPhase >= 2.0 * Math.PI) modPhase -= 2.0 * Math.PI;
				}
				break;

			case FX_CLIP_DECAY:
				// 3. Hard Clipping и Envelope Decay
				// Hard Clipping
				double minHardDrive = 1.0; double maxHardDrive = 5.0;
				double hardDrive = minHardDrive + (maxHardDrive - minHardDrive) * (paramLevel / 100.0);
				double threshold = 1.0 / hardDrive;
				for (int i = 0; i < wetBuffer.length; i++) {
					double x = wetBuffer[i];
					if (x > threshold) wetBuffer[i] = threshold; else if (x < -threshold) wetBuffer[i] = -threshold;
				}
				// Envelope
				double attackTime = 0.05; double minDecayTime = 0.1; double maxDecayTime = 0.5;
				double decayTime = maxDecayTime - (maxDecayTime - minDecayTime) * (paramLevel / 100.0);
				int attackSamples = (int) (attackTime * sampleRate);
				int decaySamples = (int) (decayTime * sampleRate);
				int startDecay = Math.min(attackSamples, wetBuffer.length / 4);
				for (int i = 0; i < wetBuffer.length; i++) {
					double env = 1.0;
					if (i < attackSamples) env = (double) i / attackSamples;
					else if (i < startDecay + decaySamples) env = 1.0 - (double) (i - startDecay) / decaySamples;
					else env = 0.05;
					if (env < 0) env = 0;
					wetBuffer[i] *= env;
				}
				break;

			case FX_REAL_DRIVE:
				// 4. Real Drive (Усиление)
				double overallDrive = 1.0 + paramLevel / 50.0;
				for (int i = 0; i < wetBuffer.length; i++) {
					wetBuffer[i] *= overallDrive;
				}
				break;

			case FX_REAL_SATURATION:
				// 5. Real Saturation (Soft Clipping через Tanh)
				double satAmount = 1.0 + paramLevel / 20.0;
				for (int i = 0; i < wetBuffer.length; i++) {
					wetBuffer[i] = Math.tanh(wetBuffer[i] * satAmount);
				}
				break;

			case FX_REAL_BITCRUSH:
				// 6. Real Bitcrush (Квантование)
				int effectiveBitDepth = Math.max(1, 16 - paramLevel / 6);
				double maxQuantization = Math.pow(2, effectiveBitDepth) - 1;
				for (int i = 0; i < wetBuffer.length; i++) {
					double normalizedSample = wetBuffer[i];
					wetBuffer[i] = Math.round(normalizedSample * maxQuantization) / maxQuantization;
				}
				break;
		}

		// Смешивание и копирование обратно в основной буфер
		mixSignal(dryBuffer, wetBuffer, mixLevel);
		System.arraycopy(wetBuffer, 0, buffer, 0, buffer.length);
	}
}
