# GitHub Copilot for Eclipse: the chat mode list misses custom agents (OPEN-51)

Status: found and analysed 26 Nov 2026; not yet reported to Microsoft. Work on it when there is time.
Tracked in the README "Status and tracking" table as OPEN-51.

## Summary

In GitHub Copilot for Eclipse 0.22, the chat mode button (Ask / Agent / Plan / Debugger / custom agents) does not show
custom agents until they have been loaded, and Copilot only loads them when the user clicks the button or opens
Configure Agents. After Eclipse starts with a custom agent selected, the button is blank and the list lacks the agent.
This is a Copilot bug, not ours: it affects every custom agent in `.github/agents/*.agent.md`, ours included.

## Environment

- Eclipse 2025-12 JEE package (test Eclipse `C:\All_Softwares\eclipse`), Windows.
- GitHub Copilot for Eclipse `0.22.0.202611251609_nightly`.
- Custom agent: `spring-boot-debugging/.github/agents/agentic_debugger.agent.md`, written by our project menu
  "Agentic Debugger > Add Agentic Debugger Agent for GitHub Copilot".
- Copilot source read: github.com/microsoft/copilot-for-eclipse, commit `6dc0170` (25 Nov 2026).

## Steps to reproduce

1. Put a custom agent in a project: `.github/agents/<name>.agent.md` with `name:` and `description:` front matter.
2. In Copilot Chat, choose that agent in the mode list.
3. Restart Eclipse (or remove the agent file and add it again while the chat is open).
4. Look at the mode button in Copilot Chat.

## What happens

- The mode button is blank (only the arrow), tooltip "Set Agents".
- The input box still shows the agent's description, so Copilot still has the agent selected.
- Opening the list shows Ask, Agent, Plan, Debugger and Configure Agents..., but not the custom agent. While the
  list opens, the button label appears.
- Preferences > GitHub Copilot > Custom Agents lists the agent correctly (name, project, description).

## What should happen

The button shows the selected agent's name, and the list contains the custom agents, from the moment the chat opens.

## Cause (in Copilot's source)

1. `com.microsoft.copilot.eclipse.ui.chat.ActionBar.setUpChatModePicker` binds the button and reloads the modes only on
   a click:

   ```java
   userPreferenceService.bindChatModePicker(this.modePickerButton);

   // Add a listener to reload modes when dropdown is about to be shown
   this.modePickerButton.addListener(SWT.MouseDown, event -> {
     userPreferenceService.reloadChatModes();
   });
   ```

2. `UserPreferenceService.reloadChatModes()` calls `CustomChatModeManager.INSTANCE.syncCustomModesFromService()`, which
   runs in the background, and only then updates the list. On the first click the list is already open with the old
   items; the update arrives afterwards (that is when the label appears).
3. `UserPreferenceService.init()` restores the selected mode by its ID (custom agents are kept by ID), but at startup
   nothing calls `reloadChatModes()`, so `buildChatModeGroups` has no custom agents.
4. `com.microsoft.copilot.eclipse.ui.swt.DropdownButton` draws an empty label when the selected ID is not among its
   items (its Javadoc: "If no matching item is found the display text is empty").

## Proposed fix (one line in Copilot)

In `ActionBar.setUpChatModePicker`, load the modes once when the button is bound, not only on a click:

```java
userPreferenceService.bindChatModePicker(this.modePickerButton);
userPreferenceService.reloadChatModes(); // load custom agents now, so the selected one has a label
```

Optionally, `DropdownButton` could show the selected ID's name (or "Loading...") instead of an empty label until the
items arrive.

To check the fix: build Copilot for Eclipse with the line, select a custom agent, restart Eclipse; the button shows the
agent's name and the list contains it on the first click.

## Workarounds for users (until Copilot is fixed)

- **Confirmed 26 Nov:** open the mode list > **Configure Agents...**, close it; the list and the button are right.
- **Expected, not yet tried:** click the mode button, close the list, click it again; the second list contains the
  agent (the first click starts the reload).

Our plugin shows the confirmed workaround in the Add action's message and in the README FAQ.

## Why our plugin does not work around it

`UserPreferenceService.reloadChatModes()` is public, but its package is exported only to Copilot's own test bundle
(`x-friends`), that is internal API. Calling it after our Add / Remove would fix those two cases only; fixing the startup
case would mean running our code at every Eclipse start, also for users who do not use our agent, which goes against
our rule not to affect them. It could also break with any Copilot update. The fix belongs in Copilot.

## To do (when there is time)

1. Try the "click twice" workaround and update this file.
2. Open an issue at github.com/microsoft/copilot-for-eclipse (draft below).
3. Optionally send the one-line pull request (draft below); check the repository's contributing rules and CLA first.
4. When Copilot ships the fix: note the version here and in the README Copilot section, and remove the workaround from
   the Add message if no supported version still needs it.

## Draft issue

**Title:** Chat mode button is blank and custom agents are missing from the mode list until the list is clicked

**Body:**

> Copilot for Eclipse 0.22.0 (nightly 202611251609), Eclipse 2025-12, Windows.
>
> Steps: add a custom agent in `.github/agents/x.agent.md`, select it in Copilot Chat, restart Eclipse.
>
> Result: the mode button is blank (the input still shows the agent's description). Opening the list shows only the
> built-in modes; the custom agent appears after the list has been opened once, or after Configure Agents... is opened.
>
> Expected: the selected custom agent's name on the button and the agent in the list from the start.
>
> Cause: `ActionBar.setUpChatModePicker` calls `reloadChatModes()` only on `SWT.MouseDown`, so custom modes are never
> loaded at startup; `DropdownButton` draws an empty label for a selected ID it cannot find; the reload on click is
> asynchronous, so the first list opens before it completes.
>
> Suggested fix: call `userPreferenceService.reloadChatModes()` once right after `bindChatModePicker(...)` in
> `ActionBar.setUpChatModePicker`.

## Draft pull request description

> **Load custom chat modes when the mode picker is created**
>
> The mode picker reloaded custom agents only on a mouse click, so after a restart a selected custom agent had no
> label and was missing from the first list. This calls `reloadChatModes()` once when the picker is bound.
>
> Tested: custom agent selected, Eclipse restarted; the button shows the agent and the first list contains it.
