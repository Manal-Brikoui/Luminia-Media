
import { ApplicationConfig, importProvidersFrom } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth-interceptor';
import {
  LucideAngularModule,
  Heart, MessageCircle, Star, Bookmark,
  CheckCircle, XCircle, Megaphone, Settings, Bell,
} from 'lucide-angular';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    importProvidersFrom(
      LucideAngularModule.pick({ Heart, MessageCircle, Star, Bookmark, CheckCircle, XCircle, Megaphone, Settings, Bell })
    ),
  ],
};