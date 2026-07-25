package dev.rstrickland.chat.ui.chat;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    public MessageAdapter(String myUserId, boolean isGroup, NameResolver names) {
        this.myUserId = myUserId;
        this.isGroup = isGroup;
        this.names = names;
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
            h.bubble.setText("🚫 message deleted");
            h.bubble.setTextColor(ContextCompat.getColor(h.bubble.getContext(), R.color.onSurfaceVariant));
            h.bubble.setTypeface(h.bubble.getTypeface(), Typeface.ITALIC);
            h.bubble.setBackgroundResource(R.drawable.bg_bubble_deleted);
        } else {
            h.bubble.setText(m.body != null ? m.body : "");
            h.bubble.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            h.bubble.setBackgroundResource(mine ? R.drawable.bg_bubble_mine : R.drawable.bg_bubble_theirs);
            h.bubble.setTextColor(ContextCompat.getColor(
                    h.bubble.getContext(), mine ? R.color.bubble_mine_text : R.color.bubble_theirs_text));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final LinearLayout row;
        final TextView sender;
        final TextView bubble;

        VH(@NonNull View v) {
            super(v);
            row = v.findViewById(R.id.messageRow);
            sender = v.findViewById(R.id.sender);
            bubble = v.findViewById(R.id.bubble);
        }
    }
}
