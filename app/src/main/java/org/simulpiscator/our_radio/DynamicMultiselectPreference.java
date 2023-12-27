package org.simulpiscator.our_radio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.preference.DialogPreference;
import android.util.AttributeSet;

public class DynamicMultiselectPreference extends DialogPreference {

    static class Data {
        CharSequence[] names = new CharSequence[0];
        CharSequence[] ids = new CharSequence[0];
        boolean[] checked = new boolean[0];
    }

    static Data unserializeData(String s) {
        Data expanded = new Data();
        if (s != null && !s.isEmpty()) {
            String[] entries = s.split("\n");
            expanded.names = new CharSequence[entries.length];
            expanded.ids = new CharSequence[entries.length];
            expanded.checked = new boolean[entries.length];
            for (int i = 0; i < entries.length; ++i) {
                String[] fields = entries[i].split("\t", 3);
                expanded.ids[i] = fields[0];
                expanded.checked[i] = fields[1].equals("1");
                expanded.names[i] = fields[2];
            }
        }
        return expanded;
    }

    static String serializeData(Data data) {
        StringBuilder s = new StringBuilder();
        if (data.names.length > 0) {
            appendEntry(data, 0, s);
        }
        for (int i = 1; i < data.names.length; ++i) {
            s.append("\n");
            appendEntry(data, i, s);
        }
        return s.toString();
    }

    private static void appendEntry(Data data, int i, StringBuilder s) {
        s
                .append(data.ids[i])
                .append("\t")
                .append(data.checked[i] ? "1" : "0")
                .append("\t")
                .append(data.names[i])
        ;
    }

    private Data mData = new Data();

    public DynamicMultiselectPreference(Context context, AttributeSet attr) {
        super(context, attr);
    }

    public DynamicMultiselectPreference(Context context, AttributeSet attr, int defaultResId) {
        super(context, attr, defaultResId);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getString(index);
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        String value = (String) defaultValue;
        if (restorePersistedValue && shouldPersist())
            value = getSharedPreferences().getString(getKey(), "");
        mData = unserializeData(value);
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setMultiChoiceItems(mData.names, mData.checked, new DialogInterface.OnMultiChoiceClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                mData.checked[which] = isChecked;
            }
        });
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (!positiveResult)
            mData = unserializeData(getSharedPreferences().getString(getKey(), ""));
        else if (shouldPersist())
            persistString(serializeData(mData));
    }

}
