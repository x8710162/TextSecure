package org.thoughtcrime.securesms;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.view.ActionMode;
import android.text.ClipboardManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.loaders.ConversationLoader;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.recipients.RecipientFactory;
import org.thoughtcrime.securesms.recipients.Recipients;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.Dialogs;
import org.thoughtcrime.securesms.util.DirectoryHelper;
import org.thoughtcrime.securesms.util.FutureTaskListener;
import org.thoughtcrime.securesms.util.ProgressDialogAsyncTask;
import org.thoughtcrime.securesms.util.SaveAttachmentTask;
import org.thoughtcrime.securesms.util.SaveAttachmentTask.Attachment;

import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.LinkedList;
import java.util.List;

public class ConversationFragment extends ListFragment
  implements LoaderManager.LoaderCallbacks<Cursor>
{
  private static final String TAG = ConversationFragment.class.getSimpleName();

  private final ActionModeCallback     actionModeCallback     = new ActionModeCallback();
  private final SelectionClickListener selectionClickListener = new SelectionClickListener();

  private ConversationFragmentListener listener;

  private MasterSecret masterSecret;
  private Recipients   recipients;
  private long         threadId;
  private ActionMode   actionMode;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    return inflater.inflate(R.layout.conversation_fragment, container, false);
  }

  @Override
  public void onActivityCreated(Bundle bundle) {
    super.onActivityCreated(bundle);

    initializeResources();
    initializeListAdapter();
    initializeContextualActionBar();
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    this.listener = (ConversationFragmentListener)activity;
  }

  private void initializeResources() {
    String recipientIds = this.getActivity().getIntent().getStringExtra("recipients");

    this.masterSecret = this.getActivity().getIntent().getParcelableExtra("master_secret");
    this.recipients   = RecipientFactory.getRecipientsForIds(getActivity(), recipientIds, true);
    this.threadId     = this.getActivity().getIntent().getLongExtra("thread_id", -1);
  }

  private void initializeListAdapter() {
    if (this.recipients != null && this.threadId != -1) {
      this.setListAdapter(new ConversationAdapter(getActivity(), masterSecret, selectionClickListener,
                                                  new FailedIconClickHandler(),
                                                  (!this.recipients.isSingleRecipient()) || this.recipients.isGroupRecipient(),
                                                  DirectoryHelper.isPushDestination(getActivity(), this.recipients)));
      getListView().setRecyclerListener((ConversationAdapter)getListAdapter());
      getLoaderManager().initLoader(0, null, this);
    }
  }

  private void initializeContextualActionBar() {
    getListView().setOnItemClickListener(selectionClickListener);
    getListView().setOnItemLongClickListener(selectionClickListener);
  }

  private void setCorrectMenuVisibility(Menu menu) {
    ConversationAdapter adapter        = (ConversationAdapter) getListAdapter();
    List<MessageRecord> messageRecords = getSelectedMessageRecords();

    if (actionMode != null && messageRecords.size() == 0) {
      adapter.getBatchSelected().clear();
      adapter.notifyDataSetChanged();
      actionMode.finish();
      return;
    }

    if (messageRecords.size() > 1) {
      menu.findItem(R.id.menu_context_forward).setVisible(false);
      menu.findItem(R.id.menu_context_copy).setVisible(false);
      menu.findItem(R.id.menu_context_details).setVisible(false);
      menu.findItem(R.id.menu_context_save_attachment).setVisible(false);
      menu.findItem(R.id.menu_context_resend).setVisible(false);
    } else {
      MenuItem resend         = menu.findItem(R.id.menu_context_resend);
      MenuItem saveAttachment = menu.findItem(R.id.menu_context_save_attachment);

      if (messageRecords.get(0).isFailed()) resend.setVisible(true);
      else                                  resend.setVisible(false);

      if (messageRecords.get(0).isMms() && !messageRecords.get(0).isMmsNotification()) {
        saveAttachment.setVisible(((MediaMmsMessageRecord) messageRecords.get(0)).containsMediaSlide());
      } else {
        saveAttachment.setVisible(false);
      }

      menu.findItem(R.id.menu_context_forward).setVisible(true);
      menu.findItem(R.id.menu_context_details).setVisible(true);
      menu.findItem(R.id.menu_context_copy).setVisible(true);
    }
  }

  private MessageRecord getSelectedMessageRecord() {
    List<MessageRecord> messageRecords = getSelectedMessageRecords();

    if (messageRecords.size() == 1) return messageRecords.get(0);
    else                            throw new AssertionError();
  }

  private List<MessageRecord> getSelectedMessageRecords() {
    return new LinkedList<>(((ConversationAdapter)getListAdapter()).getBatchSelected());
  }

  public void reload(Recipients recipients, long threadId) {
    this.recipients = recipients;
    this.threadId   = threadId;

    initializeListAdapter();
  }

  public void scrollToBottom() {
    final ListView list = getListView();
    list.post(new Runnable() {
      @Override
      public void run() {
        list.setSelection(getListAdapter().getCount() - 1);
      }
    });
  }

  private void handleCopyMessage(MessageRecord message) {
    String body = message.getDisplayBody().toString();
    if (body == null) return;

    ClipboardManager clipboard = (ClipboardManager)getActivity()
        .getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setText(body);
  }

  private void handleDeleteMessages(final List<MessageRecord> messageRecords) {
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
    builder.setTitle(R.string.ConversationFragment_confirm_message_delete);
    builder.setIcon(Dialogs.resolveIcon(getActivity(), R.attr.dialog_alert_icon));
    builder.setCancelable(true);
    builder.setMessage(R.string.ConversationFragment_are_you_sure_you_want_to_permanently_delete_all_selected_messages);
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        new ProgressDialogAsyncTask<MessageRecord, Void, Void>(getActivity(),
                                                               R.string.ConversationFragment_deleting,
                                                               R.string.ConversationFragment_deleting_messages)
        {
          @Override
          protected Void doInBackground(MessageRecord... messageRecords) {
            for (MessageRecord messageRecord : messageRecords) {
              if (messageRecord.isMms()) {
                DatabaseFactory.getMmsDatabase(getActivity()).delete(messageRecord.getId());
              } else {
                DatabaseFactory.getSmsDatabase(getActivity()).deleteMessage(messageRecord.getId());
              }
            }

            return null;
          }
        }.execute(messageRecords.toArray(new MessageRecord[messageRecords.size()]));
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleDisplayDetails(MessageRecord message) {
    long dateReceived = message.getDateReceived();
    long dateSent     = message.getDateSent();

    String transport;

    if      (message.isPending()) transport = getString(R.string.ConversationFragment_pending);
    else if (message.isPush())    transport = getString(R.string.ConversationFragment_push);
    else if (message.isMms())     transport = getString(R.string.ConversationFragment_mms);
    else                          transport = getString(R.string.ConversationFragment_sms);

    String dateFormatPattern;

    if (DateFormat.is24HourFormat(getActivity().getApplicationContext())) {
      dateFormatPattern = "EEE MMM d, yyyy 'at' HH:mm:ss zzz";
    } else {
      dateFormatPattern = "EEE MMM d, yyyy 'at' hh:mm:ss a zzz";
    }

    SimpleDateFormat    dateFormatter = new SimpleDateFormat(dateFormatPattern);
    AlertDialog.Builder builder       = new AlertDialog.Builder(getActivity());
    builder.setTitle(R.string.ConversationFragment_message_details);
    builder.setIcon(Dialogs.resolveIcon(getActivity(), R.attr.dialog_info_icon));
    builder.setCancelable(true);

    if (dateReceived == dateSent || message.isOutgoing()) {
      builder.setMessage(String.format(getActivity()
                                       .getString(R.string.ConversationFragment_transport_s_sent_received_s),
                                       transport,
                                       dateFormatter.format(new Date(dateSent))));
    } else {
      builder.setMessage(String.format(getActivity()
                                       .getString(R.string.ConversationFragment_sender_s_transport_s_sent_s_received_s),
                                       message.getIndividualRecipient().getNumber(),
                                       transport,
                                       dateFormatter.format(new Date(dateSent)),
                                       dateFormatter.format(new Date(dateReceived))));
    }

    builder.setPositiveButton(android.R.string.ok, null);
    builder.show();
  }

  private void handleForwardMessage(MessageRecord message) {
    Intent composeIntent = new Intent(getActivity(), ShareActivity.class);
    composeIntent.putExtra(ConversationActivity.DRAFT_TEXT_EXTRA, message.getDisplayBody().toString());
    composeIntent.putExtra(ShareActivity.MASTER_SECRET_EXTRA, masterSecret);
    startActivity(composeIntent);
  }

  private void handleResendMessage(final MessageRecord message) {
    final Context context = getActivity().getApplicationContext();
    new AsyncTask<MessageRecord, Void, Void>() {
      @Override
      protected Void doInBackground(MessageRecord... messageRecords) {
        MessageSender.resend(context, masterSecret, messageRecords[0]);
        return null;
      }
    }.execute(message);
  }

  private void handleSaveAttachment(final MediaMmsMessageRecord message) {
    SaveAttachmentTask.showWarningDialog(getActivity(), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int which) {

        message.fetchMediaSlide(new FutureTaskListener<Slide>() {
          @Override
          public void onSuccess(Slide slide) {
            SaveAttachmentTask saveTask = new SaveAttachmentTask(getActivity(), masterSecret);
            saveTask.execute(new Attachment(slide.getUri(), slide.getContentType(), message.getDateReceived()));
          }

          @Override
          public void onFailure(Throwable error) {
            Log.w(TAG, "No slide with attachable media found, failing nicely.");
            Log.w(TAG, error);
            Toast.makeText(getActivity(), R.string.ConversationFragment_error_while_saving_attachment_to_sd_card, Toast.LENGTH_LONG).show();
          }
        });
      }
    });
  }

  @Override
  public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
    return new ConversationLoader(getActivity(), threadId);
  }

  @Override
  public void onLoadFinished(Loader<Cursor> arg0, Cursor cursor) {
    ((CursorAdapter)getListAdapter()).changeCursor(cursor);
  }

  @Override
  public void onLoaderReset(Loader<Cursor> arg0) {
    ((CursorAdapter)getListAdapter()).changeCursor(null);
  }

  private class FailedIconClickHandler extends Handler {
    @Override
    public void handleMessage(android.os.Message message) {
      if (listener != null) {
        listener.setComposeText((String)message.obj);
      }
    }
  }

  public interface ConversationFragmentListener {
    public void setComposeText(String text);
  }

  public class SelectionClickListener
      implements AdapterView.OnItemLongClickListener, AdapterView.OnItemClickListener
  {
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
      if (actionMode != null && view instanceof ConversationItem) {
        MessageRecord messageRecord = ((ConversationItem)view).getMessageRecord();
        ((ConversationAdapter) getListAdapter()).toggleBatchSelected(messageRecord);
        ((ConversationAdapter) getListAdapter()).notifyDataSetChanged();

        setCorrectMenuVisibility(actionMode.getMenu());
      }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
      if (actionMode == null && view instanceof ConversationItem) {
        MessageRecord messageRecord = ((ConversationItem)view).getMessageRecord();
        ((ConversationAdapter) getListAdapter()).toggleBatchSelected(messageRecord);
        ((ConversationAdapter) getListAdapter()).notifyDataSetChanged();

        actionMode = ((ActionBarActivity)getActivity()).startSupportActionMode(actionModeCallback);
        return true;
      }

      return false;
    }
  }

  private class ActionModeCallback implements ActionMode.Callback {

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      MenuInflater inflater = mode.getMenuInflater();
      inflater.inflate(R.menu.conversation_context, menu);

      setCorrectMenuVisibility(menu);
      return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
      return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
      ((ConversationAdapter)getListAdapter()).getBatchSelected().clear();
      ((ConversationAdapter)getListAdapter()).notifyDataSetChanged();

      actionMode = null;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
      switch(item.getItemId()) {
        case R.id.menu_context_copy:
          handleCopyMessage(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        case R.id.menu_context_delete_message:
          handleDeleteMessages(getSelectedMessageRecords());
          actionMode.finish();
          return true;
        case R.id.menu_context_details:
          handleDisplayDetails(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        case R.id.menu_context_forward:
          handleForwardMessage(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        case R.id.menu_context_resend:
          handleResendMessage(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        case R.id.menu_context_save_attachment:
          handleSaveAttachment((MediaMmsMessageRecord)getSelectedMessageRecord());
          actionMode.finish();
          return true;
      }

      return false;
    }
  };
}
