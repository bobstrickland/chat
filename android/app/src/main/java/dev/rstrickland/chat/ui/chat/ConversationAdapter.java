package dev.rstrickland.chat.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import dev.rstrickland.chat.R;
import dev.rstrickland.chat.model.ChatModels.ConversationRow;
import dev.rstrickland.chat.net.NameResolver;

/** The conversation list. Tapping a row opens that conversation. */
public final class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.VH> {

    public interface OnOpen {
        void open(ConversationRow row);
    }

    private final List<ConversationRow> items = new ArrayList<>();
    private final OnOpen onOpen;
    private final NameResolver names;

    public ConversationAdapter(OnOpen onOpen, NameResolver names) {
        this.onOpen = onOpen;
        this.names = names;
    }

    public void submit(List<ConversationRow> rows) {
        items.clear();
        if (rows != null) items.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_conversation, parent, false);
        return new VH(v);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ConversationRow row = items.get(position);

        boolean hasLast = row.lastMessage != null;
        h.preview.setText(hasLast && row.lastMessage.body != null && !row.lastMessage.body.isEmpty()
                ? row.lastMessage.body : "—");
        h.time.setText(hasLast ? formatTime(row.lastMessage.sentAt) : "");
        h.itemView.setOnClickListener(v -> onOpen.open(row));

        if ("group".equals(row.type)) {
            String name = row.name != null ? row.name : "Group";
            h.title.setText("# " + name);
            h.avatar.setText(initials(name));
        } else {
            // Direct: show the peer's DISPLAY NAME, never their userId. Resolve
            // async; tag the row so a recycled holder doesn't get a stale name.
            String key = row.conversationId;
            h.itemView.setTag(key);
            h.title.setText(NameResolver.LOADING);
            h.avatar.setText("");
            names.resolve(row.peerId, name -> {
                if (key.equals(h.itemView.getTag())) {
                    h.title.setText(name);
                    h.avatar.setText(initials(name));
                }
            });
        }
    }

    private static String initials(String name) {
        if (name == null || name.isEmpty()) return "?";
        String trimmed = name.replaceFirst("^[#\\s]+", "");
        return trimmed.isEmpty() ? "?" : trimmed.substring(0, 1).toUpperCase();
    }

    private static String formatTime(String iso) {
        try {
            return TIME.format(Instant.parse(iso).atZone(ZoneId.systemDefault()));
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView avatar;
        final TextView title;
        final TextView preview;
        final TextView time;

        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            title = v.findViewById(R.id.title);
            preview = v.findViewById(R.id.preview);
            time = v.findViewById(R.id.time);
        }
    }
}
