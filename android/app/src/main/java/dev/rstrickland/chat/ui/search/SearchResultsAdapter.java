package dev.rstrickland.chat.ui.search;

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
import dev.rstrickland.chat.model.SearchModels.MessageHit;
import dev.rstrickland.chat.model.SearchModels.SearchResults;
import dev.rstrickland.chat.model.SearchModels.UserHit;
import dev.rstrickland.chat.net.NameResolver;

/**
 * Flattened search results: a "People" section (person rows) followed by a
 * "Messages" section (message rows), each with a header. A person row lets you
 * start a direct chat or add the user as a contact; a message row opens its
 * conversation. Sender names are resolved to display names — never a userId.
 */
public final class SearchResultsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Actions {
        void onAddContact(UserHit user);

        void onMessageUser(UserHit user);

        void onOpenMessage(MessageHit message);

        boolean isContact(String userId);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PERSON = 1;
    private static final int TYPE_MESSAGE = 2;

    /** A section title row. */
    private static final class Header {
        final String title;

        Header(String title) {
            this.title = title;
        }
    }

    private final List<Object> rows = new ArrayList<>();
    private final Actions actions;
    private final NameResolver names;

    public SearchResultsAdapter(Actions actions, NameResolver names) {
        this.actions = actions;
        this.names = names;
    }

    public void submit(SearchResults results) {
        rows.clear();
        if (results != null) {
            if (results.users != null && !results.users.isEmpty()) {
                rows.add(new Header("People"));
                rows.addAll(results.users);
            }
            if (results.messages != null && !results.messages.isEmpty()) {
                rows.add(new Header("Messages"));
                rows.addAll(results.messages);
            }
        }
        notifyDataSetChanged();
    }

    public void clear() {
        rows.clear();
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    @Override
    public int getItemViewType(int position) {
        Object row = rows.get(position);
        if (row instanceof Header) return TYPE_HEADER;
        if (row instanceof UserHit) return TYPE_PERSON;
        return TYPE_MESSAGE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_HEADER:
                return new HeaderVH(inf.inflate(R.layout.item_search_header, parent, false));
            case TYPE_PERSON:
                return new PersonVH(inf.inflate(R.layout.item_search_person, parent, false));
            default:
                return new MessageVH(inf.inflate(R.layout.item_search_message, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).header.setText(((Header) row).title);
        } else if (holder instanceof PersonVH) {
            bindPerson((PersonVH) holder, (UserHit) row);
        } else {
            bindMessage((MessageVH) holder, (MessageHit) row);
        }
    }

    private void bindPerson(PersonVH h, UserHit user) {
        String label = user.label();
        h.name.setText(label);
        h.avatar.setText(initial(label));

        boolean contact = user.userId != null && actions.isContact(user.userId);
        h.addButton.setVisibility(contact ? View.GONE : View.VISIBLE);
        h.addedLabel.setVisibility(contact ? View.VISIBLE : View.GONE);

        h.addButton.setOnClickListener(v -> actions.onAddContact(user));
        h.itemView.setOnClickListener(v -> actions.onMessageUser(user)); // tap row -> direct chat
    }

    private void bindMessage(MessageVH h, MessageHit message) {
        h.snippet.setText(message.body != null ? message.body : "");
        h.itemView.setOnClickListener(v -> actions.onOpenMessage(message));

        // Resolve the sender to a display name; guard against recycling with a tag.
        String key = message.messageId;
        h.itemView.setTag(key);
        h.sender.setText(NameResolver.LOADING);
        names.resolve(message.senderId, name -> {
            if (key != null && key.equals(h.itemView.getTag())) h.sender.setText(name);
        });
    }

    private static String initial(String name) {
        return (name == null || name.trim().isEmpty()) ? "?" : name.trim().substring(0, 1).toUpperCase();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView header;

        HeaderVH(@NonNull View v) {
            super(v);
            header = v.findViewById(R.id.header);
        }
    }

    static final class PersonVH extends RecyclerView.ViewHolder {
        final TextView avatar;
        final TextView name;
        final MaterialButton addButton;
        final TextView addedLabel;

        PersonVH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.avatar);
            name = v.findViewById(R.id.name);
            addButton = v.findViewById(R.id.addButton);
            addedLabel = v.findViewById(R.id.addedLabel);
        }
    }

    static final class MessageVH extends RecyclerView.ViewHolder {
        final TextView sender;
        final TextView snippet;

        MessageVH(@NonNull View v) {
            super(v);
            sender = v.findViewById(R.id.sender);
            snippet = v.findViewById(R.id.snippet);
        }
    }
}
