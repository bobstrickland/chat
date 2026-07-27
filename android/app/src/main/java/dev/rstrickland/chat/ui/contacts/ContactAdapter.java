package dev.rstrickland.chat.ui.contacts;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import dev.rstrickland.chat.R;
import dev.rstrickland.chat.model.ContactModels.Contact;

/** The contact list. Each row can start a direct chat or remove the contact. */
public final class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {

    public interface Actions {
        void onMessage(Contact contact);

        void onRemove(Contact contact);
    }

    private final List<Contact> items = new ArrayList<>();
    private final Actions actions;

    public ContactAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<Contact> rows) {
        items.clear();
        if (rows != null) items.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Contact c = items.get(position);
        String label = c.label();
        h.name.setText(label);
        h.avatar.setText(initial(label));
        h.messageButton.setOnClickListener(v -> actions.onMessage(c));
        h.removeButton.setOnClickListener(v -> actions.onRemove(c));
    }

    private static String initial(String name) {
        return (name == null || name.trim().isEmpty()) ? "?" : name.trim().substring(0, 1).toUpperCase();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView avatar;
        final TextView name;
        final MaterialButton messageButton;
        final MaterialButton removeButton;

        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            name = v.findViewById(R.id.name);
            messageButton = v.findViewById(R.id.messageButton);
            removeButton = v.findViewById(R.id.removeButton);
        }
    }
}
