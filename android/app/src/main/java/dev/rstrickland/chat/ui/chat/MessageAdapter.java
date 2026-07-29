package dev.rstrickland.chat.ui.chat;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import dev.rstrickland.chat.R;
import dev.rstrickland.chat.model.ChatModels.Message;
import dev.rstrickland.chat.net.NameResolver;
import dev.rstrickland.chat.net.api.MediaApi;

/**
 * Messages in one conversation. One row layout serves both sides: the bind sets
 * the row gravity (mine = end) and bubble color. In a group, the sender's name
 * shows above others' messages. Deleted messages render as a tombstone.
 */
public final class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    private final List<Message> items = new ArrayList<>();
    private final String myUserId;
    private final boolean isGroup;
    private final NameResolver names;
    private final MessageMediaBinder media;

    // The PEER's furthest read/delivered position (ISO sentAt) — drives ticks on
    // MY messages. Direct conversations only (per-member group ticks are out of scope).
    private String peerRead;
    private String peerDelivered;

    public MessageAdapter(String myUserId, boolean isGroup, NameResolver names, MediaApi mediaApi) {
        this.myUserId = myUserId;
        this.isGroup = isGroup;
        this.names = names;
        this.media = new MessageMediaBinder(mediaApi);
    }

    /** Release playback resources (audio player) — call from the fragment's onDestroyView. */
    public void release() {
        media.release();
    }

    /**
     * Advance a peer's receipt position (forward-only) and refresh the ticks.
     * ISO instant strings sort lexicographically, so string compare == time compare.
     */
    public void applyPeerReceipt(String kind, String position) {
        if (position == null || position.isEmpty()) return;
        if ("read".equals(kind)) {
            if (peerRead == null || position.compareTo(peerRead) > 0) peerRead = position;
        } else if ("delivered".equals(kind)) {
            if (peerDelivered == null || position.compareTo(peerDelivered) > 0) peerDelivered = position;
        } else {
            return;
        }
        notifyDataSetChanged(); // cheap: refreshes the tick indicators
    }

    /** read / delivered / sent for one of MY messages (direct convos only). */
    private String statusOf(Message m) {
        if (m.sentAt == null) return "sent";
        if (peerRead != null && peerRead.compareTo(m.sentAt) >= 0) return "read";
        if (peerDelivered != null && peerDelivered.compareTo(m.sentAt) >= 0) return "delivered";
        return "sent";
    }

    public void submit(List<Message> messages) {
        items.clear();
        if (messages != null) items.addAll(messages);
        notifyDataSetChanged();
    }

    /** Append a live/new message, de-duplicating by messageId. Returns true if added. */
    public boolean append(Message m) {
        for (Message existing : items) {
            if (existing.messageId.equals(m.messageId)) return false;
        }
        items.add(m);
        notifyItemInserted(items.size() - 1);
        return true;
    }

    /** Mark a message deleted-for-everyone (tombstone) in place, by id. */
    public void markDeleted(String messageId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).messageId.equals(messageId)) {
                items.get(i).deleted = true;
                items.get(i).body = "";
                notifyItemChanged(i);
                return;
            }
        }
    }

    public int size() {
        return items.size();
    }

    public Message last() {
        return items.isEmpty() ? null : items.get(items.size() - 1);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Message m = items.get(position);
        boolean mine = myUserId != null && myUserId.equals(m.senderId);

        h.row.setGravity(mine ? Gravity.END : Gravity.START);

        if (isGroup && !mine) {
            // Show the sender's DISPLAY NAME, never their userId; resolve async.
            h.sender.setVisibility(View.VISIBLE);
            String sid = m.senderId;
            h.sender.setTag(sid);
            h.sender.setText(NameResolver.LOADING);
            names.resolve(sid, name -> {
                if (sid.equals(h.sender.getTag())) h.sender.setText(name);
            });
        } else {
            h.sender.setVisibility(View.GONE);
        }

        if (m.deleted) {
            // Tombstone: text only, no media.
            media.bind(null, mine, h.mediaContainer, h.mediaImage, h.playOverlay, h.mediaChip);
            h.bubble.setVisibility(View.VISIBLE);
            h.bubble.setText("🚫 message deleted");
            h.bubble.setTextColor(ContextCompat.getColor(h.bubble.getContext(), R.color.onSurfaceVariant));
            h.bubble.setTypeface(h.bubble.getTypeface(), Typeface.ITALIC);
            h.bubble.setBackgroundResource(R.drawable.bg_bubble_deleted);
        } else {
            media.bind(m.mediaId, mine, h.mediaContainer, h.mediaImage, h.playOverlay, h.mediaChip);

            boolean hasBody = m.body != null && !m.body.isEmpty();
            boolean hasMedia = m.mediaId != null && !m.mediaId.isEmpty();
            // A media-only message hides the empty text bubble; a caption still shows.
            if (!hasBody && hasMedia) {
                h.bubble.setVisibility(View.GONE);
            } else {
                h.bubble.setVisibility(View.VISIBLE);
                h.bubble.setText(m.body != null ? m.body : "");
                h.bubble.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                h.bubble.setBackgroundResource(mine ? R.drawable.bg_bubble_mine : R.drawable.bg_bubble_theirs);
                h.bubble.setTextColor(ContextCompat.getColor(
                        h.bubble.getContext(), mine ? R.color.bubble_mine_text : R.color.bubble_theirs_text));
            }
        }

        bindStatusTick(h, m, mine);
    }

    /** ✓ / ✓✓ / ✓✓(read) under MY direct messages; hidden otherwise. */
    private void bindStatusTick(VH h, Message m, boolean mine) {
        if (!mine || isGroup || m.deleted) {
            h.status.setVisibility(View.GONE);
            return;
        }
        String st = statusOf(m);
        h.status.setVisibility(View.VISIBLE);
        h.status.setText("sent".equals(st) ? "✓" : "✓✓"); // delivered & read both double-check
        int color = "read".equals(st) ? R.color.primary : R.color.onSurfaceVariant;
        h.status.setTextColor(ContextCompat.getColor(h.status.getContext(), color));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final LinearLayout row;
        final TextView sender;
        final TextView bubble;
        final FrameLayout mediaContainer;
        final ImageView mediaImage;
        final ImageView playOverlay;
        final TextView mediaChip;
        final TextView status;

        VH(@NonNull View v) {
            super(v);
            row = v.findViewById(R.id.messageRow);
            sender = v.findViewById(R.id.sender);
            bubble = v.findViewById(R.id.bubble);
            mediaContainer = v.findViewById(R.id.mediaContainer);
            mediaImage = v.findViewById(R.id.mediaImage);
            playOverlay = v.findViewById(R.id.playOverlay);
            mediaChip = v.findViewById(R.id.mediaChip);
            status = v.findViewById(R.id.status);
        }
    }
}
