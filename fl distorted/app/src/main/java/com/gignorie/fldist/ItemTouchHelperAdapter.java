package com.gignorie.fldist;

public interface ItemTouchHelperAdapter {
	/**
	* Вызывается, когда элемент был перемещен с одной позиции на другую.
	* @param fromPosition Начальная позиция элемента.
	* @param toPosition Конечная позиция элемента.
	*/
	void onItemMove(int fromPosition, int toPosition);
}