package dev.rstrickland.chat.ui.chat;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.rstrickland.chat.R;
import dev.rstrickland.chat.databinding.FragmentChatBinding;
import dev.rstrickland.chat.model.ChatModels;
import dev.rstrickland.chat.model.MediaModels;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.ConversationIds;
import dev.rstrickland.chat.net.MediaBlobClient;
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

    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean uploading;

    /** System picker for an attachment. OpenDocument accepts multiple MIME types. */
    private final ActivityResultLauncher<String[]> pickMedia =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onMediaPicked);

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

        io = Executors.newCachedThreadPool();

        views.backButton.setOnClickListener(v -> showList());
        views.sendButton.setOnClickListener(v -> send());
        views.attachButton.setOnClickListener(v ->
                pickMedia.launch(new String[]{"image/*", "video/*", "audio/*"}));

        Bundle args = getArguments();
        pendingOpenId = args != null ? args.getString(ARG_OPEN) : null;

        RealtimeClient.get().addListener(this);
        loadConversations();
    }

    @Override
    public void onDestroyView() {
        RealtimeClient.get().removeListener(this);
        if (messageAdapter != null) messageAdapter.release();
        if (io != null) io.shutdownNow();
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

        if (messageAdapter != null) messageAdapter.release(); // stop any audio from the last convo
        messageAdapter = new MessageAdapter(myUserId, isGroup, names, api.media());
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
                    // Offline sync: apply the peer's current read/delivered positions
                    // so ticks reflect state that changed while we were away.
                    if (res.body().receipts != null) {
                        for (ChatModels.Receipt r : res.body().receipts) {
                            if (!myUserId.equals(r.userId)) {
                                messageAdapter.applyPeerReceipt(r.kind, r.position);
                            }
                        }
                    }
                    scrollToBottom();
                    markViewedRead(); // I'm now looking at this conversation
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

    // ---- media attachment ----

    /**
     * Picked an attachment: read its bytes, run the three-step Media upload
     * (presign → PUT → enqueue processing), then send a media-only message
     * carrying the resulting mediaId. All network work is off the main thread.
     */
    private void onMediaPicked(@Nullable Uri uri) {
        if (uri == null || openConversationId == null || uploading || io == null) return;
        final String conversationId = openConversationId;
        final ContentResolver resolver = requireContext().getContentResolver();
        final String contentType = mimeOf(resolver, uri);

        uploading = true;
        setComposerBusy(true);

        io.execute(() -> {
            try {
                byte[] bytes = readBytes(resolver, uri);

                MediaModels.CreateUploadResponse up = body(
                        api.media().createUpload(new MediaModels.CreateUploadRequest(contentType)).execute());
                if (up == null) throw new Exception("could not start upload");
                MediaBlobClient.get().put(up.uploadUrl, bytes, contentType);
                api.media().complete(up.mediaId).execute();

                // Media-only message (no caption), mirroring the web client.
                ChatModels.Message sent = body(api.messaging()
                        .send(conversationId, new ChatModels.SendRequest(null, up.mediaId)).execute());

                main.post(() -> {
                    uploading = false;
                    if (views == null) return;
                    setComposerBusy(false);
                    if (sent != null && messageAdapter != null && conversationId.equals(openConversationId)) {
                        messageAdapter.append(sent);
                        scrollToBottom();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    uploading = false;
                    if (views == null) return;
                    setComposerBusy(false);
                    Toast.makeText(requireContext(),
                            "Attachment failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** Disable the composer controls while an upload is in flight. */
    private void setComposerBusy(boolean busy) {
        if (views == null) return;
        views.attachButton.setEnabled(!busy);
        views.sendButton.setEnabled(!busy);
        views.composer.setHint(busy ? "Uploading attachment…" : "Message…");
    }

    private static String mimeOf(ContentResolver resolver, Uri uri) {
        String type = resolver.getType(uri);
        return type != null ? type : "application/octet-stream";
    }

    private static <T> T body(Response<T> res) {
        return res.isSuccessful() ? res.body() : null;
    }

    private static byte[] readBytes(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("could not open the selected file");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
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
            // A message I can see (not my own echo) is one I've read.
            if (!myUserId.equals(m.senderId)) markViewedRead();
        } else if ("receipt".equals(type)) {
            // The peer advanced their read/delivered position — update my ticks.
            if (!myUserId.equals(frame.optString("userId"))) {
                messageAdapter.applyPeerReceipt(frame.optString("kind"), frame.optString("position"));
            }
        } else if ("message-deleted".equals(type)) {
            messageAdapter.markDeleted(frame.optString("messageId"));
        }
    }

    /** Tell the server I've read up to the latest message (fire-and-forget). */
    private void markViewedRead() {
        if (openConversationId == null || messageAdapter == null) return;
        ChatModels.Message last = messageAdapter.last();
        if (last == null || last.sentAt == null || last.sentAt.isEmpty()) return;
        api.messaging()
                .sendReceipt(openConversationId, new ChatModels.ReceiptRequest("read", last.sentAt))
                .enqueue(IGNORE);
    }

    /** A no-op callback for fire-and-forget receipt posts. */
    private static final Callback<Void> IGNORE = new Callback<>() {
        @Override
        public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> res) {
        }

        @Override
        public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
        }
    };
}
