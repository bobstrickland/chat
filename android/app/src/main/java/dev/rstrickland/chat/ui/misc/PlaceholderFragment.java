package dev.rstrickland.chat.ui.misc;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import dev.rstrickland.chat.R;

/**
 * A simple "coming soon" screen for nav destinations not yet built (Search,
 * Contacts). They'll be replaced by real fragments in the next increment — the
 * API layer already exists, so they're cheap to add.
 */
public final class PlaceholderFragment extends Fragment {
    private static final String ARG_TEXT = "text";

    public static PlaceholderFragment of(String text) {
        PlaceholderFragment f = new PlaceholderFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TEXT, text);
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_placeholder, container, false);
        TextView text = root.findViewById(R.id.placeholderText);
        text.setText(getArguments() != null ? getArguments().getString(ARG_TEXT) : "");
        return root;
    }
}
