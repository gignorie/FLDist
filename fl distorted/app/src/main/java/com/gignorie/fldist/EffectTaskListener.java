package com.gignorie.fldist;

// Интерфейс для обратной связи от фоновых задач к Activity

public interface EffectTaskListener {
	void onPreviewTaskComplete(String tempPath);
	void onApplyTaskComplete(Boolean success);
	String executeRootCommand(String command); // Передаем Root-логику сюда
}
