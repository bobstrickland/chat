package dev.rstrickland.chat.ui.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.json.JSONObject;

import dev.rstrickland.chat.R;
import dev.rstrickland.chat.databinding.FragmentChatBinding;
import dev.rstrickland.chat.model.ChatModels;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.ConversationIds;
import dev.rstrickland.chat.net.NameResolver;
import dev.rstrickland.chat.net.TokenStore;
import dev.rstrickland.chat.realtime.RealtimeClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Two modes in one fragment (a phone doesn't fit the web's two-pane): the
 * conversation LIST, and an OPEN conversation (messages + composer). Live
 * messages arrive over the shared WebSocket; only frames for the open
 * conversation are appended.
 */
public final class ChatFragment extends Fragment implements RealtimeClient.FrameListener {

    private static final String ARG_OPEN = "openConversationId";

    /**
     * A ChatFragment that jumps straight into {@code conversationId} once its
     * list has loaded — used when Search or Contacts sends the user into a chat.
     */
    public static ChatFragment openingConversation(String conversationId) {
        ChatFragment f = new ChatFragment();
        Bundle args = new Bundle();
        args.putString(ARG_OPEN, conversationId);
        f.setArguments(args);
        return f;
    }

    private FragmentChatBinding views;
    private ApiClient api;
    private String myUserId;

    private ConversationAdapter conversationAdapter;
    private MessageAdapter messageAdapter;
    private NameResolver names;
    private String openConversationId; // null while showing the list

    private java.util.List<ChatModels.ConversationRow> loaded = new java.util.ArrayList<>();
    private String pendingOpenId; // a conversation to auto-open once the list loads

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        views = FragmentChatBinding.inflate(inflater, container, false);
        return views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        api = ApiClient.get(requireContext());
        myUserId = TokenStore.get(requireContext()).userId();
        names = NameResolver.get(requireContext());

        conversationAdapter = new ConversationAdapter(this::openConversation, names, api.media());
        views.rvConversations.setLayoutManager(new LinearLayoutManager(requireContext()));
        views.rvConversations.setLayoutAnimation(
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_animation));
        views.rvConversations.setAdapter(conversationAdapter);

        LinearLayoutManager messagesLm = new LinearLayoutManager(requireContext());
        messagesLm.setStackFromEnd(true); // keep newest at the bottom
        views.rvMessages.setLayoutManager(messagesLm);

        views.backButton.setOnClickListener(v -> showList());
        views.sendButton.setOnClickListener(v -> send());

        Bundle args = getArguments();
        pendingOpenId = args != null ? args.getString(ARG_OPEN) : null;

        RealtimeClient.get().addListener(this);
        loadConversations();
    }

    @Override
    public void onDestroyView() {
        RealtimeClient.get().removeListener(this);
        super.onDestroyView();
        views = null;
    }

    // ---- conversation list ----

    private void loadConversations() {
        api.messaging().listConversations().enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ChatModels.ConversationsResponse> call,
                                   @NonNull Response<ChatModels.ConversationsResponse> res) {
                if (views != null && res.body() != null) {
                    loaded = res.body().conversations != null
                            ? res.body().conversations : new java.util.ArrayList<>();
                    conversationAdapter.submit(loaded);
                    views.rvConversations.scheduleLayoutAnimation();
                    maybeOpenPending();
                }
            }

            @Override
            public void onFailure(@NonNull Call<ChatModels.ConversationsResponse> call,
                                  @NonNull Throwable t) {
                // Leave the list as-is; a transient error resolves on next open.
            }
        });
    }

    private void showList() {
        openConversationId = null;
        views.conversationView.setVisibility(View.GONE);
        views.rvConversations.setVisibility(View.VISIBLE);
        views.rvConversations.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in));
        loadConversations(); // refresh previews
    }

    // ---- open conversation ----

    private void openConversation(ChatModels.ConversationRow row) {
        openConversationId = row.conversationId;
        boolean isGroup = row.conversationId.startsWith("grp#");

        // Title: group name, or the peer's DISPLAY NAME (resolved async) — never a userId.
        if ("group".equals(row.type)) {
            views.conversationTitle.setText("# " + (row.name != null ? row.name : "Group"));
        } else {
            String convId = row.conversationId;
            views.conversationTitle.setText(NameResolver.LOADING);
            names.resolve(row.peerId, name -> {
                if (views != null && convId.equals(openConversationId)) {
                    views.conversationTitle.setText(name);
                }
            });
        }

        messageAdapter = new MessageAdapter(myUserId, isGroup, names);
        views.rvMessages.setAdapter(messageAdapter);

        views.rvConversations.setVisibility(View.GONE);
        views.conversationView.setVisibility(View.VISIBLE);
        views.conversationView.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in));

        api.messaging().history(row.conversationId).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ChatModels.HistoryResponse> call,
                                   @NonNull Response<ChatModels.HistoryResponse> res) {
                if (views != null && res.body() != null && row.conversationId.equals(openConversationId)) {
                    messageAdapter.submit(res.body().messages);
                    scrollToBottom();
                }
            }

            @Override
            public void onFailure(@NonNull Call<ChatModels.HistoryResponse> call, @NonNull Throwable t) {
            }
        });
    }

    /**
     * If we were asked to jump into a conversation (from Search/Contacts), open it
     * once the list has loaded — preferring the loaded row (for its name/peer),
     * else a row derived from the id. Runs once; then clears the pending id.
     */
    private void maybeOpenPending() {
        if (pendingOpenId == null) return;
        String id = pendingOpenId;
        pendingOpenId = null;

        ChatModels.ConversationRow row = null;
        for (ChatModels.ConversationRow r : loaded) {
            if (id.equals(r.conversationId)) {
                row = r;
                break;
            }
        }
        openConversation(row != null ? row : deriveRow(id));
    }

    /** A minimal row for a conversation the list doesn't (yet) contain. */
    private ChatModels.ConversationRow deriveRow(String conversationId) {
        ChatModels.ConversationRow row = new ChatModels.ConversationRow();
        row.conversationId = conversationId;
        if (ConversationIds.isGroup(conversationId)) {
            row.type = "group";
        } else {
            row.type = "direct";
            row.peerId = ConversationIds.peerOf(conversationId, myUserId);
        }
        return row;
    }

    private void send() {
        if (openConversationId == null) return;
        String body = views.composer.getText().toString().trim();
        if (body.isEmpty()) return;
        views.composer.setText("");
        api.messaging().send(openConversationId, new ChatModels.SendRequest(body, null))
                .enqueue(new Callback<>() {
                    @Override
                    public void onResponse(@NonNull Call<ChatModels.Message> call,
                                           @NonNull Response<ChatModels.Message> res) {
                        if (views != null && res.body() != null && messageAdapter != null) {
                            messageAdapter.append(res.body());
                            scrollToBottom();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ChatModels.Message> call, @NonNull Throwable t) {
                        if (views != null) views.composer.setText(body); // restore for retry
                    }
                });
    }

    private void scrollToBottom() {
        if (messageAdapter != null && messageAdapter.size() > 0) {
            views.rvMessages.scrollToPosition(messageAdapter.size() - 1);
        }
    }

    // ---- live frames ----

    @Override
    public void onFrame(JSONObject frame) {
        if (views == null || openConversationId == null || messageAdapter == null) return;
        String type = frame.optString("type");
        if (!openConversationId.equals(frame.optString("conversationId"))) return;

        if ("message".equals(type)) {
            ChatModels.Message m = new ChatModels.Message();
            m.conversationId = frame.optString("conversationId");
            m.messageId = frame.optString("messageId");
            m.senderId = frame.optString("senderId");
            m.body = frame.optString("body");
            m.sentAt = frame.optString("sentAt");
            m.mediaId = frame.isNull("mediaId") ? null : frame.optString("mediaId", null);
            if (messageAdapter.append(m)) scrollToBottom();
        } else if ("message-deleted".equals(type)) {
            messageAdapter.markDeleted(frame.optString("messageId"));
        }
    }
}
