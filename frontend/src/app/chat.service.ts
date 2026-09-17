import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Reponse des endpoints multimodaux ; transcription n'est renseignee que pour l'audio. */
export interface ChatAnswer {
  answer: string;
  transcription: string | null;
}

export type AttachmentKind = 'image' | 'audio' | 'pdf';

/**
 * Endpoints reels du chatbot, joints via l'API Gateway. L'URL est relative (/chatbot/...) : Nginx en Docker,
 * ou le proxy de ng serve en developpement, relaient ces appels vers la Gateway.
 * - GET  /chat?query=...          texte  -> text/plain
 * - POST /chat/image (file, query) image  -> JSON ChatAnswer
 * - POST /chat/audio (file)        audio  -> JSON ChatAnswer (avec transcription)
 * - POST /chat/pdf   (file, query) PDF    -> JSON ChatAnswer
 * Toute la logique IA (ReAct, MCP, RAG, vision, speech-to-text) reste dans le backend.
 */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/chatbot/chat';

  ask(query: string): Observable<string> {
    return this.http.get(`${this.baseUrl}?query=${encodeURIComponent(query)}`, { responseType: 'text' });
  }

  askWithFile(kind: AttachmentKind, file: File, query: string): Observable<ChatAnswer> {
    const form = new FormData();
    form.append('file', file, file.name);
    if (kind !== 'audio' && query) {
      form.append('query', query);
    }
    return this.http.post<ChatAnswer>(`${this.baseUrl}/${kind}`, form);
  }
}
