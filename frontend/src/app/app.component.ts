import { Component, ElementRef, OnDestroy, inject, signal, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { AttachmentKind, ChatService } from './chat.service';

interface Attachment {
  kind: AttachmentKind;
  file: File;
  url: string;
}

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  time: Date;
  attachment?: Attachment;
  transcription?: string;
  error?: boolean;
}

const MAX_FILE_SIZE = 10 * 1024 * 1024;

@Component({
  selector: 'app-root',
  imports: [FormsModule, DatePipe],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnDestroy {
  private readonly chatService = inject(ChatService);
  private readonly scrollArea = viewChild<ElementRef<HTMLElement>>('scrollArea');
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');
  private recorder?: MediaRecorder;
  private readonly objectUrls: string[] = [];

  readonly messages = signal<ChatMessage[]>([
    {
      role: 'assistant',
      content: "Bonjour, je suis l'assistant EBank. Posez une question, ou joignez une image, un PDF ou un audio.",
      time: new Date()
    }
  ]);
  readonly loading = signal(false);
  readonly online = signal(true);
  readonly attachment = signal<Attachment | null>(null);
  readonly recording = signal(false);
  readonly notice = signal<string | null>(null);
  draft = '';

  canSend(): boolean {
    return !this.loading() && !this.recording() && (this.draft.trim().length > 0 || this.attachment() !== null);
  }

  openFilePicker(): void {
    this.fileInput()?.nativeElement.click();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) {
      this.attach(file);
    }
  }

  removeAttachment(): void {
    this.attachment.set(null);
  }

  async toggleRecording(): Promise<void> {
    if (this.recording()) {
      this.recorder?.stop();
      return;
    }
    this.notice.set(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const chunks: Blob[] = [];
      this.recorder = new MediaRecorder(stream);
      this.recorder.ondataavailable = e => chunks.push(e.data);
      this.recorder.onstop = () => {
        stream.getTracks().forEach(track => track.stop());
        this.recording.set(false);
        const type = (this.recorder?.mimeType || 'audio/webm').split(';')[0];
        const extension = type.includes('ogg') ? 'ogg' : type.includes('mp4') ? 'm4a' : 'webm';
        this.attach(new File(chunks, `enregistrement.${extension}`, { type }));
      };
      this.recorder.start();
      this.recording.set(true);
    } catch {
      this.notice.set("Micro indisponible : autorisez l'accès au micro ou joignez un fichier audio.");
    }
  }

  send(): void {
    if (!this.canSend()) {
      return;
    }
    const query = this.draft.trim();
    const attachment = this.attachment();
    this.draft = '';
    this.attachment.set(null);
    this.notice.set(null);
    this.addMessage({
      role: 'user',
      content: query || (attachment ? this.defaultLabel(attachment.kind) : ''),
      time: new Date(),
      attachment: attachment ?? undefined
    });
    this.loading.set(true);

    const request: Observable<{ answer: string; transcription: string | null }> = attachment
      ? this.chatService.askWithFile(attachment.kind, attachment.file, query)
      : this.chatService.ask(query).pipe(map(answer => ({ answer, transcription: null })));

    request.subscribe({
      next: ({ answer, transcription }) => {
        this.online.set(true);
        if (transcription) {
          this.messages.update(messages => {
            const last = messages[messages.length - 1];
            return [...messages.slice(0, -1), { ...last, transcription }];
          });
        }
        this.addMessage({ role: 'assistant', content: answer, time: new Date() });
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        const unreachable = err.status === 0 || err.status === 502 || err.status === 503 || err.status === 504;
        this.online.set(!unreachable);
        this.addMessage({
          role: 'assistant',
          content: unreachable
            ? 'Impossible de contacter le serveur. Vérifiez que les services EBank sont démarrés.'
            : this.errorMessage(err),
          time: new Date(),
          error: true
        });
        this.loading.set(false);
      }
    });
  }

  ngOnDestroy(): void {
    this.objectUrls.forEach(url => URL.revokeObjectURL(url));
  }

  private attach(file: File): void {
    const kind = this.kindOf(file);
    if (!kind) {
      this.notice.set('Format non pris en charge : choisissez une image, un PDF ou un fichier audio.');
      return;
    }
    if (file.size > MAX_FILE_SIZE) {
      this.notice.set('Fichier trop volumineux (10 Mo maximum).');
      return;
    }
    const url = URL.createObjectURL(file);
    this.objectUrls.push(url);
    this.notice.set(null);
    this.attachment.set({ kind, file, url });
  }

  private kindOf(file: File): AttachmentKind | null {
    if (file.type.startsWith('image/')) return 'image';
    if (file.type.startsWith('audio/') || file.type === 'video/webm') return 'audio';
    if (file.type === 'application/pdf') return 'pdf';
    return null;
  }

  private defaultLabel(kind: AttachmentKind): string {
    return kind === 'image' ? 'Décris cette image.' : kind === 'pdf' ? 'Résume ce document.' : 'Message vocal';
  }

  private errorMessage(err: HttpErrorResponse): string {
    if (err.status === 413) return 'Fichier trop volumineux pour le serveur (10 Mo maximum).';
    if (err.status === 415) return 'Type de fichier refusé par le serveur.';
    if (err.status === 422 && err.error?.message) return err.error.message;
    return `Le serveur a renvoyé une erreur (HTTP ${err.status}). Veuillez réessayer.`;
  }

  private addMessage(message: ChatMessage): void {
    this.messages.update(messages => [...messages, message]);
    setTimeout(() => {
      const area = this.scrollArea()?.nativeElement;
      if (area) {
        area.scrollTop = area.scrollHeight;
      }
    });
  }
}
