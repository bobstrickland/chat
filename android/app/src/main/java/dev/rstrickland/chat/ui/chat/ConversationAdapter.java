package dev.rstrickland.chat.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
import dev.rstrickland.chat.net.AvatarLoader;
import dev.rstrickland.chat.net.NameResolver;
import dev.rstrickland.chat.net.api.MediaApi;

/** The conversation list. Tapping a row opens that conversation. */
public final class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.VH> {

    public interface OnOpen {
        void open(ConversationRow row);
    }

    private final List<ConversationRow> items = new ArrayList<>();
    private final OnOpen onOpen;
    private final NameResolver names;

    private final MediaApi mediaApi;
    public ConversationAdapter(OnOpen onOpen, NameResolver names, MediaApi mediaApi) {
        this.onOpen = onOpen;
        this.names = names;
        this.mediaApi = mediaApi;
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

        h.preview.setText(previewOf(row));
        h.time.setText(row.lastMessage != null ? formatTime(row.lastMessage.sentAt) : "");
        h.itemView.setOnClickListener(v -> onOpen.open(row));

        // Reset the photo every bind so a recycled row never shows a stale avatar
        // (the initials underneath show through until/unless a photo loads).
        AvatarLoader.load(mediaApi, null, h.avatarImage);

        if ("group".equals(row.type)) {
            String name = row.name != null ? row.name : "Group";
            h.title.setText("# " + name);
            h.avatar.setText(initials(name)); // groups keep initials (no avatar)
        } else {
            // Direct: show the peer's DISPLAY NAME + avatar, never their userId.
            // Both come from ONE profile fetch (NameResolver). Tag the row so a
            // recycled holder doesn't get a stale result.
            String key = row.conversationId;
            h.itemView.setTag(key);
            h.title.setText(NameResolver.LOADING);
            h.avatar.setText("");
            names.resolveIdentity(row.peerId, (name, avatarMediaId) -> {
                if (!key.equals(h.itemView.getTag())) return; // recycled — drop
                h.title.setText(name);
                if (NameResolver.LOADING.equals(name)) return; // still resolving
                h.avatar.setText(initials(name));
                row.avatarMediaId = avatarMediaId; // populate the row
                AvatarLoader.load(mediaApi, avatarMediaId, h.avatarImage);
            });
        }
    }

    /** List preview: the last message body, or a label for a media-only message. */
    private static String previewOf(ConversationRow row) {
        if (row.lastMessage == null) return "—";
        String body = row.lastMessage.body;
        if (body != null && !body.isEmpty()) return body;
        String mediaId = row.lastMessage.mediaId;
        if (mediaId != null && !mediaId.isEmpty()) return "📎 Attachment";
        return "—";
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
        final ImageView avatarImage;

        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            title = v.findViewById(R.id.title);
            preview = v.findViewById(R.id.preview);
            time = v.findViewById(R.id.time);
            avatarImage = v.findViewById(R.id.avatarImage);
        }
    }
}
