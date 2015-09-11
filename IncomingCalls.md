# Processing of Incoming Calls #

This page details the processing of incoming calls in rVoix.

Basically, the service first filters all incoming calls using black/white lists, and then handles remaining calls
according to default settings specified for different call types as well as to settings for individual phone numbers.

The processing of all (filtered or not) calls may also be overridden by auto-answer settings which may effectively suppress the default processing.

The call types are:

  * number in contacts;
  * number not in contacts;
  * unknown numbers.

The default settings for each call type are:

  * none (do not process at all);
  * record the call;
  * hang up;
  * mute the ring (further recording is impossible if you put the phone off-hook);
  * in-call manual control.

The last option is experimental, and may be used to start/stop recording of multiple call fragments during the call.

These settings are defined in main "Incoming calls" menu.

As for call filtering, you may maintain multiple black and white lists, quickly reloading them using "Load" command from menu.

Only **one** list (either black or white) may be active at each specific time moment.
This is defined by "Blacklists and exceptions -> B/W lists mode" setting (see Figure below).
Switching to white lists may be useful, e.g., when you are busy and inclined to speak only to a limited number of your callers.

The "Black lists and exceptions->Exceptions" list for individual numbers is ignored when a black or white list
is active (i.e., the B/W lists mode is not "Off"): it is used to override further default processing of (known) numbers
for the first two call types.

However, the "Autoanswer settings->Exceptions" list may be used to override the default auto-answer files for those lists.
As shown in the following Figure, an auto-answer is _always immediate_ for black and white lists, moreover, in case of black lists,
the callers' answers are never recorded.

![http://img3.imageshack.us/img3/8017/drawing01.png](http://img3.imageshack.us/img3/8017/drawing01.png)

The next two Figures demonstrate how the auto-answering is implemented.

a) _Black and white lists_

![http://img11.imageshack.us/img11/4721/drawing02.png](http://img11.imageshack.us/img11/4721/drawing02.png)

b) _Regular calls_

![http://img194.imageshack.us/img194/7623/drawing03.png](http://img194.imageshack.us/img194/7623/drawing03.png)

The delay in this Figure is defined by "Autoanswer delay" setting either for the respective incoming call type, or for individual numbers if the number is found in "Autoanswer settings->Exceptions".

File and Delay settings for "Incoming calls->Blacklists and exceptions->Exceptions" are taken from "Autoanswer settings->Exceptions," but the auto-answer mode (i.e., "autoanswer" or "autoanswer+rec") of the former takes priority over the latter.
For example, consider the following setup:

  * "Numbers in contacts": "Ask on each call"
  * "Blacklists and exceptions->Exceptions": "Autoanswer+record" exception type for a number +12345678 (which is in contacts)
  * "Autoanswer settings->Numbers in contacts": "Autoanswer" mode, files "contacts.wav" and "contacts\_rec.wav" for "Autoanswer" and "Autoanswer+record" correspondingly, delay of 10 seconds
  * "Autoanswer settings->Exceptions": delay of 15 seconds, and file "12345678.wav" for a number +12345678 of type "Auto-answer" in Exceptions list.

As the result, +12345678 will be auto-answered and recorded after 15 seconds (because it's in exception list) using "contacts\_rec.wav" (because its type doesn't match that defined in "Autoanswer settings->Exceptions"). If you take the phone off-hook before this 15 second interval expires, auto-answer and further recording will be cancelled, you'll be asked if the call needs recording, and will be able to complete the call yourself.

If +12345678 were not in "Blacklists and exceptions->Exceptions" list, it would be auto-answered (no recording) using "12345678.wav" after 15 seconds. If it were not in "Autoanswer settings->Exceptions" also, "contacts.wav" would be used after 10 seconds.

Note that if "Autoanswer" mode in "Autoanswer settings->Numbers ..." is "Off," no auto-answer is ever initiated unless the number is in "Blacklists and exceptions->Exceptions."

**So, if you have a device that does not support auto-answering, make sure that the modes for all call types in "Autoanswer settings" are "Off," do not select auto-answering modes for white lists, and check that no number has "Autoanswer" or "Autoanswer+record" type in Blacklist or in Exceptions**.

Pressing "Cancel" button when an auto-answer or caller's recording is in progress will fall back to the mode which would be used if the auto-answer hadn't been activated. That is, everything that the caller might have said before this moment will be saved as usual in an "A"-prefixed file, and the subsequent part of your conversation might be recorded in an "I"-prefixed file.








