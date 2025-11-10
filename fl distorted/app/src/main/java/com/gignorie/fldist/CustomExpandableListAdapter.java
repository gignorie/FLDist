package com.gignorie.fldist;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

public class CustomExpandableListAdapter extends BaseExpandableListAdapter {

    private Context context;
    private List<String> groupList; // Список родительских ключей (PTH_1, PTH_2, ...)
    private Map<String, List<String>> childMap; // Карта дочерних элементов (WAV-файлов)
    private OnChildClickListener childClickListener;

    // Интерфейс для обработки кликов, чтобы MainActivity знала о нажатии
    public interface OnChildClickListener {
        void onWavFileClicked(String wavPath);
    }

    public CustomExpandableListAdapter(Context context, List<String> groupList, 
                                     Map<String, List<String>> childMap, 
                                     OnChildClickListener listener) {
        this.context = context;
        this.groupList = groupList;
        this.childMap = childMap;
        this.childClickListener = listener;
    }

    // --- Методы для групп (Родители - PTH) ---

    @Override
    public int getGroupCount() {
        return groupList.size();
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    @Override
    public Object getGroup(int groupPosition) {
        return groupList.get(groupPosition);
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, 
                             View convertView, ViewGroup parent) {
        
        String groupTitle = (String) getGroup(groupPosition);
        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(android.R.layout.simple_expandable_list_item_1, null);
        }
        
        TextView listTitle = (TextView) convertView.findViewById(android.R.id.text1);
        listTitle.setTypeface(null, Typeface.BOLD);
        listTitle.setText("Track ID: " + groupTitle); // Отображаем PTH-ключ
        return convertView;
    }

    // --- Методы для дочерних элементов (Дети - WAV) ---
    
    @Override
    public int getChildrenCount(int groupPosition) {
        return childMap.get(groupList.get(groupPosition)).size();
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        return childMap.get(groupList.get(groupPosition)).get(childPosition);
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, 
                             View convertView, ViewGroup parent) {
        
        final String childText = (String) getChild(groupPosition, childPosition);
        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(android.R.layout.simple_list_item_1, null);
        }

        TextView txtListChild = (TextView) convertView.findViewById(android.R.id.text1);
        // Убираем My Recordings/ для более чистого отображения
        String displayPath = childText.replace("My Recordings/", "");
        txtListChild.setText(displayPath);
        
        // --- Обработка нажатия на WAV-файл ---
        convertView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (childClickListener != null) {
                    Toast.makeText(context, "Selected: " + displayPath, Toast.LENGTH_SHORT).show();
                    childClickListener.onWavFileClicked(childText); // Передаем полный путь
                }
            }
        });
        
        return convertView;
    }

    // --- Обязательные, но не критичные методы ---
    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }
}
