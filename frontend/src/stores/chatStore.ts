import { create } from "zustand";
import { toast } from "sonner";

import type { FeedbackValue, Message, Session } from "@/types";
import {
  listMessages,
  listSessions,
  deleteSession as deleteSessionRequest,
  renameSession as renameSessionRequest
} from "@/services/sessionService";
import { stopTask, submitFeedback } from "@/services/chatService";
import { storage } from "@/utils/storage";

interface ChatState {
  sessions: Session[];
  currentSessionId: string | null;
  messages: Message[];
  isLoading: boolean;
  sessionsLoaded: boolean;
  inputFocusKey: number;
  isStreaming: boolean;
  isCreatingNew: boolean;
  deepThinkingEnabled: boolean;
  thinkingStartAt: number | null;
  streamTaskId: string | null;
  streamAbort: (() => void) | null;
  streamingMessageId: string | null;
  cancelRequested: boolean;
  activeKbId: string;
  setActiveKbId: (kbId: string) => void;
  fetchSessions: () => Promise<void>;
  createSession: () => Promise<string>;
  deleteSession: (sessionId: string) => Promise<void>;
  renameSession: (sessionId: string, title: string) => Promise<void>;
  selectSession: (sessionId: string) => Promise<void>;
  updateSessionTitle: (sessionId: string, title: string) => void;
  setDeepThinkingEnabled: (enabled: boolean) => void;
  sendMessage: (content: string) => Promise<void>;
  cancelGeneration: () => void;
  appendStreamContent: (delta: string) => void;
  appendThinkingContent: (delta: string) => void;
  submitFeedback: (messageId: string, feedback: FeedbackValue) => Promise<void>;
}

function mapVoteToFeedback(vote?: number | null): FeedbackValue {
  if (vote === 1) return "like";
  if (vote === -1) return "dislike";
  return null;
}

function upsertSession(sessions: Session[], next: Session) {
  const index = sessions.findIndex((session) => session.id === next.id);
  const updated = [...sessions];
  if (index >= 0) {
    updated[index] = { ...sessions[index], ...next };
  } else {
    updated.unshift(next);
  }
  return updated.sort((a, b) => {
    const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
    const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
    return timeB - timeA;
  });
}

function computeThinkingDuration(startAt?: number | null) {
  if (!startAt) return undefined;
  const seconds = Math.round((Date.now() - startAt) / 1000);
  return Math.max(1, seconds);
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

export const useChatStore = create<ChatState>((set, get) => ({
  sessions: [],
  currentSessionId: null,
  messages: [],
  isLoading: false,
  sessionsLoaded: false,
  inputFocusKey: 0,
  isStreaming: false,
  isCreatingNew: false,
  deepThinkingEnabled: false,
  thinkingStartAt: null,
  streamTaskId: null,
  streamAbort: null,
  streamingMessageId: null,
  cancelRequested: false,
  activeKbId: "2",
  setActiveKbId: (kbId) => set({ activeKbId: kbId }),
  fetchSessions: async () => {
    set({ isLoading: true });
    try {
      const data = await listSessions();
      const sessions = data
        .map((item) => ({
        id: item.conversationId,
        title: item.title || "新对话",
        lastTime: item.lastTime
        }))
        .sort((a, b) => {
          const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
          const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
          return timeB - timeA;
        });
      set({ sessions });
    } catch (error) {
      toast.error((error as Error).message || "加载会话失败");
    } finally {
      set({ isLoading: false, sessionsLoaded: true });
    }
  },
  createSession: async () => {
    const state = get();
    if (state.messages.length === 0 && !state.currentSessionId) {
      set({
        isCreatingNew: true,
        isLoading: false,
        thinkingStartAt: null,
        deepThinkingEnabled: false
      });
      return "";
    }
    if (state.isStreaming) {
      get().cancelGeneration();
    }
    set({
      currentSessionId: null,
      messages: [],
      isStreaming: false,
      isLoading: false,
      isCreatingNew: true,
      deepThinkingEnabled: false,
      thinkingStartAt: null,
      streamTaskId: null,
      streamAbort: null,
      streamingMessageId: null,
      cancelRequested: false
    });
    return "";
  },
  deleteSession: async (sessionId) => {
    try {
      await deleteSessionRequest(sessionId);
      set((state) => ({
        sessions: state.sessions.filter((session) => session.id !== sessionId),
        messages: state.currentSessionId === sessionId ? [] : state.messages,
        currentSessionId: state.currentSessionId === sessionId ? null : state.currentSessionId
      }));
      toast.success("删除成功");
    } catch (error) {
      toast.error((error as Error).message || "删除会话失败");
    }
  },
  renameSession: async (sessionId, title) => {
    const nextTitle = title.trim();
    if (!nextTitle) return;
    try {
      await renameSessionRequest(sessionId, nextTitle);
      set((state) => ({
        sessions: state.sessions.map((session) =>
          session.id === sessionId ? { ...session, title: nextTitle } : session
        )
      }));
      toast.success("已重命名");
    } catch (error) {
      toast.error((error as Error).message || "重命名失败");
    }
  },
  selectSession: async (sessionId) => {
    if (!sessionId) return;
    if (get().currentSessionId === sessionId && get().messages.length > 0) return;
    if (get().isStreaming) {
      get().cancelGeneration();
    }
    set({
      isLoading: true,
      currentSessionId: sessionId,
      isCreatingNew: false,
      thinkingStartAt: null
    });
    try {
      const data = await listMessages(sessionId);
      if (get().currentSessionId !== sessionId) {
        return;
      }
      const mapped: Message[] = data.map((item) => ({
        id: String(item.id),
        role: item.role === "assistant" ? "assistant" : "user",
        content: item.content,
        thinking: item.thinkingContent || undefined,
        thinkingDuration: item.thinkingDuration || undefined,
        isDeepThinking: Boolean(item.thinkingContent),
        createdAt: item.createTime,
        feedback: mapVoteToFeedback(item.vote),
        status: "done"
      }));
      set({ messages: mapped });
    } catch (error) {
      toast.error((error as Error).message || "加载消息失败");
    } finally {
      if (get().currentSessionId !== sessionId) {
        set({ isLoading: false });
        return;
      }
      set({
        isLoading: false,
        isStreaming: false,
        streamTaskId: null,
        streamAbort: null,
        streamingMessageId: null,
        cancelRequested: false
      });
    }
  },
  updateSessionTitle: (sessionId, title) => {
    set((state) => ({
      sessions: state.sessions.map((session) =>
        session.id === sessionId ? { ...session, title } : session
      )
    }));
  },
  setDeepThinkingEnabled: (enabled) => {
    set({ deepThinkingEnabled: enabled });
  },
  sendMessage: async (content) => {
    const trimmed = content.trim();
    if (!trimmed) return;
    if (get().isStreaming) return;
    const deepThinkingEnabled = get().deepThinkingEnabled;
    const inputFocusKey = Date.now();

    const userMessage: Message = {
      id: `user-${Date.now()}`,
      role: "user",
      content: trimmed,
      status: "done",
      createdAt: new Date().toISOString()
    };
    const assistantId = `assistant-${Date.now()}`;
    const assistantMessage: Message = {
      id: assistantId,
      role: "assistant",
      content: "",
      thinking: deepThinkingEnabled ? "" : undefined,
      isDeepThinking: deepThinkingEnabled,
      isThinking: deepThinkingEnabled,
      status: "streaming",
      feedback: null,
      createdAt: new Date().toISOString()
    };

    set((state) => ({
      messages: [...state.messages, userMessage, assistantMessage],
      isStreaming: true,
      streamingMessageId: assistantId,
      thinkingStartAt: null,
      inputFocusKey,
      streamTaskId: null,
      cancelRequested: false
    }));

    const conversationId = get().currentSessionId;
    const kbId = get().activeKbId;
    const token = storage.getToken();

    try {
      const res = await fetch(`${API_BASE_URL}/chat`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: JSON.stringify({
          message: trimmed,
          conversationId: conversationId || undefined,
          kbIds: kbId ? [kbId] : undefined
        })
      });

      if (!res.ok) {
        throw new Error(`请求失败（${res.status}）`);
      }

      const data = await res.json();

      if (get().streamingMessageId !== assistantId) return;

      const lastTime = new Date().toISOString();
      const nextConvId = data.conversationId || conversationId;

      set((state) => ({
        currentSessionId: nextConvId,
        isCreatingNew: false,
        sessions: upsertSession(state.sessions, {
          id: nextConvId,
          title: state.sessions.find((s) => s.id === nextConvId)?.title || "新对话",
          lastTime
        }),
        messages: state.messages.map((msg) =>
          msg.id === assistantId
            ? { ...msg, content: data.message, status: "done" as const }
            : msg
        ),
        isStreaming: false,
        thinkingStartAt: null,
        streamTaskId: null,
        streamAbort: null,
        streamingMessageId: null,
        cancelRequested: false
      }));
    } catch (error) {
      if (get().streamingMessageId !== assistantId) return;
      set((state) => ({
        isStreaming: false,
        thinkingStartAt: null,
        streamTaskId: null,
        streamAbort: null,
        cancelRequested: false,
        messages: state.messages.map((msg) =>
          msg.id === assistantId ? { ...msg, status: "error" as const } : msg
        )
      }));
      toast.error((error as Error).message || "生成失败");
    }
  },
  cancelGeneration: () => {
    const { isStreaming, streamTaskId } = get();
    if (!isStreaming) return;
    set({ cancelRequested: true });
    if (streamTaskId) {
      stopTask(streamTaskId).catch(() => null);
    }
  },
  appendStreamContent: (delta) => {
    if (!delta) return;
    set((state) => {
      const shouldFinalizeThinking = state.thinkingStartAt != null;
      const duration = computeThinkingDuration(state.thinkingStartAt);
      return {
        thinkingStartAt: shouldFinalizeThinking ? null : state.thinkingStartAt,
        messages: state.messages.map((message) => {
          if (message.id !== state.streamingMessageId) return message;
          if (message.status === "cancelled" || message.status === "error") return message;
          return {
            ...message,
            content: message.content + delta,
            isThinking: shouldFinalizeThinking ? false : message.isThinking,
            thinkingDuration:
              shouldFinalizeThinking && !message.thinkingDuration ? duration : message.thinkingDuration
          };
        })
      };
    });
  },
  appendThinkingContent: (delta) => {
    if (!delta) return;
    set((state) => ({
      thinkingStartAt: state.thinkingStartAt ?? Date.now(),
      messages: state.messages.map((message) =>
        message.id === state.streamingMessageId &&
        message.status !== "cancelled" &&
        message.status !== "error"
          ? {
              ...message,
              thinking: `${message.thinking ?? ""}${delta}`,
              isThinking: true
            }
          : message
      )
    }));
  },
  submitFeedback: async (messageId, feedback) => {
    const vote = feedback === "like" ? 1 : feedback === "dislike" ? -1 : null;
    const prev = get().messages.find((message) => message.id === messageId)?.feedback ?? null;
    set((state) => ({
      messages: state.messages.map((message) =>
        message.id === messageId ? { ...message, feedback } : message
      )
    }));
    if (vote === null) {
      toast.success("取消成功");
      return;
    }
    try {
      await submitFeedback(messageId, vote);
      toast.success(feedback === "like" ? "点赞成功" : "点踩成功");
    } catch (error) {
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === messageId ? { ...message, feedback: prev } : message
        )
      }));
      toast.error((error as Error).message || "反馈保存失败");
    }
  }
}));
