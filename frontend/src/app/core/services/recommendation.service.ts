import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, forkJoin, of, BehaviorSubject, concat } from 'rxjs';
import { map, catchError, switchMap, tap, timeout } from 'rxjs/operators';
import { AuthService } from './auth';
import { MediaService } from './media.service';
import { MediaResponse, ExternalMediaResponse, MediaType } from '../models/media.models';

export interface RecommendationResult {
  userId:          string;
  recommendations: MediaResponse[];
}

export interface ExternalLikedInfo {
  likedTypes:        MediaType[];
  likedExternalIds:  string[];
  likedInternalIds?: number[];
}

export interface ExternalTopics {
  PODCAST: string[];
  BOOK:    string[];
  FILM:    string[];
  GAME:    string[];
}

interface ExternalWithType {
  item:       ExternalMediaResponse;
  type:       MediaType;
  externalId: string;
}

const QUOTA_PER_TYPE: Record<MediaType, number> = {
  FILM:    6,
  BOOK:    5,
  PODCAST: 5,
  GAME:    4,
};


const EXTERNAL_CALL_TIMEOUT_MS = 4000;

@Injectable({ providedIn: 'root' })
export class RecommendationService {

  private readonly API            = 'http://localhost:8085/recommendations';
  private readonly FETCH_PER_TYPE = 30; 
  private readonly MAX_DISPLAYED  = 20;

  private topicsCache$ = new BehaviorSubject<ExternalTopics | null>(null);

  constructor(
    private http:         HttpClient,
    private auth:         AuthService,
    private mediaService: MediaService,
  ) {}

  private get headers(): HttpHeaders {
    return new HttpHeaders({ Authorization: `Bearer ${this.auth.getToken()}` });
  }

  buildExternalId(type: MediaType, item: ExternalMediaResponse): string {
    const key = item.externalId
      ? item.externalId
      : encodeURIComponent((item.title ?? 'unknown').trim().toLowerCase());
    return `EXT_${type}_${key}`;
  }

  getForMe(): Observable<RecommendationResult> {
    return this.http.get<RecommendationResult>(`${this.API}/me`, { headers: this.headers });
  }

  likedInfo(): Observable<{ likedExternalIds: string[]; likedInternalIds: number[] }> {
    return this.getExternalLikedInfo().pipe(
      map(info => ({
        likedExternalIds: info.likedExternalIds ?? [],
        likedInternalIds: info.likedInternalIds ?? [],
      }))
    );
  }

  likeExternalMedia(
    externalMediaId: string,
    likeType: 'LIKE' | 'FAVORITE' = 'LIKE',
    title: string = '',
    mediaType: string = '',
  ): Observable<ExternalTopics> {
    let finalId = externalMediaId;
    if (!externalMediaId?.startsWith('EXT_') && title && mediaType) {
      const safeTitle = encodeURIComponent(title.trim().toLowerCase());
      finalId = `EXT_${mediaType.toUpperCase()}_${safeTitle}`;
    }

    return this.http.post(
      `${this.API}/external/like`,
      { externalMediaId: finalId, likeType, title, mediaType },
      { headers: this.headers },
    ).pipe(
      switchMap(() => this.refreshTopics()),
    );
  }

  unlikeExternalMedia(externalMediaId: string, likeType: 'LIKE' | 'FAVORITE' = 'LIKE'): Observable<ExternalTopics> {
    return this.http.delete(
      `${this.API}/external/like`,
      { headers: this.headers, body: { externalMediaId, likeType } },
    ).pipe(
      switchMap(() => this.refreshTopics()),
    );
  }

  refreshTopics(): Observable<ExternalTopics> {
    return this.http.get<ExternalTopics>(
      `${this.API}/external/topics`,
      { headers: this.headers },
    ).pipe(
      tap(t => this.topicsCache$.next(t)),
      catchError(() => {
        const fallback: ExternalTopics = {
          PODCAST: ['technology', 'science', 'culture'],
          BOOK:    ['popular', 'best', 'fiction'],
          FILM:    ['popular', 'trending'],
          GAME:    ['popular'],
        };
        this.topicsCache$.next(fallback);
        return of(fallback);
      })
    );
  }

  private getExternalLikedInfo(): Observable<ExternalLikedInfo> {
    return this.http.get<ExternalLikedInfo>(
      `${this.API}/external/liked-info`,
      { headers: this.headers },
    ).pipe(
      catchError(() => of({ likedTypes: [], likedExternalIds: [], likedInternalIds: [] } as ExternalLikedInfo))
    );
  }

  getExternalTopics(): Observable<ExternalTopics> {
    const cached = this.topicsCache$.getValue();
    if (cached) return of(cached);
    return this.refreshTopics();
  }

  private getLikedInternalIds(): Observable<number[]> {
    return this.http.get<{ likedIds: number[] }>(
      `${this.API}/liked-internal-ids`,
      { headers: this.headers },
    ).pipe(
      map(res => res.likedIds ?? []),
      catchError(() => of([] as number[]))
    );
  }

  private scoreExternal(item: MediaResponse, topics: ExternalTopics, type: MediaType): number {
    const text = `${item.title} ${item.genre} ${item.description}`.toLowerCase();

    const primaryTopics   = (topics[type] ?? []).map(t => t.toLowerCase());
    const secondaryTopics = (Object.entries(topics) as [MediaType, string[]][])
      .filter(([t]) => t !== type)
      .flatMap(([, ts]) => ts)
      .map(t => t.toLowerCase());

    const uniqueSecondary = secondaryTopics.filter(t => !primaryTopics.includes(t));

    const primaryMatches  = primaryTopics.filter(w => text.includes(w));
    const primaryScore    = Math.min(primaryMatches.length, 2) * 3;
    const secondaryScore  = uniqueSecondary.filter(w => text.includes(w)).length * 1;

    return primaryScore + secondaryScore;
  }

  
  getEnrichedRecommendations(): Observable<RecommendationResult> {
    const username = this.auth.getUsername() ?? '';

    const internal$ = this.http.get<RecommendationResult>(
      `${this.API}/me`, { headers: this.headers },
    ).pipe(
      catchError(() => of({ userId: username, recommendations: [] as MediaResponse[] }))
    );

    return forkJoin({
      internal:         internal$,
      externalInfo:     this.getExternalLikedInfo(),
      externalTopics:   this.refreshTopics(),
      likedInternalIds: this.getLikedInternalIds(),
    }).pipe(
      switchMap(({ internal, externalInfo, externalTopics, likedInternalIds }) =>
        this.buildEnrichedResult(internal, externalInfo, externalTopics, likedInternalIds)
      )
    );
  }

  getEnrichedRecommendationsProgressive(): Observable<RecommendationResult> {
    const username = this.auth.getUsername() ?? '';

    const internal$ = this.http.get<RecommendationResult>(
      `${this.API}/me`, { headers: this.headers },
    ).pipe(
      catchError(() => of({ userId: username, recommendations: [] as MediaResponse[] }))
    );

    return internal$.pipe(
      switchMap(internal => {
        const initial$: Observable<RecommendationResult> = of({
          userId:          internal.userId,
          recommendations: internal.recommendations.slice(0, this.MAX_DISPLAYED),
        });

        const enriched$: Observable<RecommendationResult> = forkJoin({
          externalInfo:     this.getExternalLikedInfo(),
          externalTopics:   this.refreshTopics(),
          likedInternalIds: this.getLikedInternalIds(),
        }).pipe(
          switchMap(({ externalInfo, externalTopics, likedInternalIds }) =>
            this.buildEnrichedResult(internal, externalInfo, externalTopics, likedInternalIds)
          ),
          
          catchError(() => of({
            userId:          internal.userId,
            recommendations: internal.recommendations.slice(0, this.MAX_DISPLAYED),
          }))
        );

     
        return concat(initial$, enriched$);
      })
    );
  }

  private buildEnrichedResult(
    internal:         RecommendationResult,
    externalInfo:     ExternalLikedInfo,
    externalTopics:   ExternalTopics,
    likedInternalIds: number[],
  ): Observable<RecommendationResult> {

    const likedExternalIds = new Set<string>(externalInfo.likedExternalIds ?? []);
    const likedInternalSet = new Set<number>(likedInternalIds);

    const filteredInternal = internal.recommendations.filter(r => !likedInternalSet.has(r.id));

    const likedTypes = externalInfo.likedTypes ?? [];
    const typesToFetch = likedTypes.length > 0
      ? new Set<MediaType>(likedTypes as MediaType[])
      : new Set<MediaType>(['FILM', 'BOOK', 'GAME', 'PODCAST']);

    return this.fetchExternalByTypes(typesToFetch, externalTopics).pipe(
      map(external => {

        const internalTitles = new Set(
          filteredInternal.map(r => r.title?.toLowerCase().trim())
        );

        const externalAsMedia: MediaResponse[] = external.map(({ item, type, externalId }) => ({
          id:          0,
          title:       item.title,
          author:      item.author      ?? '',
          genre:       item.genre       ?? '',
          imageUrl:    item.coverUrl    ?? '',
          contentUrl:  item.readUrl     ?? '',
          type,
          description: item.description ?? '',
          status:      'AVAILABLE' as any,
          releaseYear: item.releaseYear,
          externalId,
        }));

        const uniqueExternal = externalAsMedia.filter(e =>
          !internalTitles.has(e.title?.toLowerCase().trim() ?? '') &&
          !likedExternalIds.has(e.externalId!)
        );

        const scoredByType: Record<string, Array<{ e: MediaResponse; score: number }>> = {
          FILM: [], BOOK: [], GAME: [], PODCAST: [],
        };

        for (const e of uniqueExternal) {
          if (!e.type || !scoredByType[e.type]) continue;
          const score = this.scoreExternal(e, externalTopics, e.type as MediaType);
          scoredByType[e.type].push({ e, score });
        }

        for (const type of Object.keys(scoredByType)) {
          scoredByType[type].sort((a, b) => b.score - a.score);
        }

        const activeTypes = [...typesToFetch] as MediaType[];
        const quotaPerType = this._computeQuota(activeTypes, this.MAX_DISPLAYED);

        const selectedByType: Record<string, MediaResponse[]> = {};
        for (const type of activeTypes) {
          const quota = quotaPerType[type] ?? 0;
          selectedByType[type] = (scoredByType[type] ?? [])
            .slice(0, quota)
            .map(s => s.e);
        }

        const typeOrder: MediaType[] = ['FILM', 'BOOK', 'PODCAST', 'GAME'];
        const interleaved: MediaResponse[] = [];
        const maxLen = Math.max(0, ...typeOrder.map(t => (selectedByType[t] ?? []).length));
        for (let i = 0; i < maxLen; i++) {
          for (const t of typeOrder) {
            const item = (selectedByType[t] ?? [])[i];
            if (item) interleaved.push(item);
          }
        }

        const merged: MediaResponse[] = [];
        const totalLen = Math.max(filteredInternal.length, interleaved.length);
        for (let i = 0; i < totalLen; i++) {
          if (filteredInternal[i]) merged.push(filteredInternal[i]);
          if (interleaved[i])      merged.push(interleaved[i]);
        }

        const final = merged.slice(0, this.MAX_DISPLAYED);

        return {
          userId:          internal.userId,
          recommendations: final,
        };
      })
    );
  }

  private _computeQuota(activeTypes: MediaType[], total: number): Record<string, number> {
    const quota: Record<string, number> = {};
    if (activeTypes.length === 0) return quota;

    const basePerType = Math.floor(total / activeTypes.length);
    let remaining = total;

    for (const t of activeTypes) {
      const q = Math.min(QUOTA_PER_TYPE[t] ?? basePerType, basePerType + 2);
      quota[t] = q;
      remaining -= q;
    }

    if (remaining > 0 && activeTypes[0]) {
      quota[activeTypes[0]] += remaining;
    }

    return quota;
  }

  private fetchExternalByTypes(
    types: Set<MediaType>,
    topics: ExternalTopics,
  ): Observable<ExternalWithType[]> {
    if (types.size === 0) return of([]);

    const calls: Observable<ExternalWithType[]>[] = [];

    const safe = <T>(obs: Observable<T[]>): Observable<T[]> =>
      obs.pipe(
        timeout(EXTERNAL_CALL_TIMEOUT_MS),
        catchError(() => of([] as T[]))
      );

    const dedup = (items: ExternalMediaResponse[], type: MediaType): ExternalWithType[] => {
      const seen = new Set<string>();
      const out: ExternalWithType[] = [];
      for (const item of items) {
        const id = this.buildExternalId(type, item);
        if (!seen.has(id)) { seen.add(id); out.push({ item, type, externalId: id }); }
      }
      return out.slice(0, this.FETCH_PER_TYPE);
    };

    if (types.has('FILM')) {
      const fTopics = topics.FILM.slice(0, 3);
      calls.push(
        forkJoin([
          ...fTopics.map(t => safe(this.mediaService.searchExternalFilms(t))),
          safe(this.mediaService.searchExternalFilmsYoutube(fTopics[0] ?? 'popular')),
          safe(this.mediaService.searchExternalFilmsArchive(fTopics[0] ?? 'popular')),
        ]).pipe(
          map(r => { const all = ([] as ExternalMediaResponse[]).concat(...r); return dedup(all, 'FILM'); }),
          catchError(() => of([]))
        )
      );
    }

    if (types.has('BOOK')) {
      const bTopics = topics.BOOK.slice(0, 4);
      calls.push(
        forkJoin(bTopics.map(t => safe(this.mediaService.searchExternalBooks(t)))).pipe(
          map(r => { const all = ([] as ExternalMediaResponse[]).concat(...r); return dedup(all, 'BOOK'); }),
          catchError(() => of([]))
        )
      );
    }

    if (types.has('GAME')) {
      const gTopics = topics.GAME.slice(0, 2);
      calls.push(
        forkJoin([
          ...gTopics.map(t => safe(this.mediaService.searchExternalGames(t))),
          safe(this.mediaService.searchExternalFreeGames('')),
        ]).pipe(
          map(r => { const all = ([] as ExternalMediaResponse[]).concat(...r); return dedup(all, 'GAME'); }),
          catchError(() => of([]))
        )
      );
    }

    if (types.has('PODCAST')) {
      const pTopics = topics.PODCAST.slice(0, 4);
      calls.push(
        forkJoin([
          ...pTopics.map(t => safe(this.mediaService.searchExternalPodcastsIndex(t))),
          ...pTopics.slice(0, 2).map(t => safe(this.mediaService.searchExternalPodcastsItunes(t))),
        ]).pipe(
          map(r => { const all = ([] as ExternalMediaResponse[]).concat(...r); return dedup(all, 'PODCAST'); }),
          catchError(() => of([]))
        )
      );
    }

    if (calls.length === 0) return of([]);
    return forkJoin(calls).pipe(
      map(results => ([] as ExternalWithType[]).concat(...results)),
      catchError(() => of([]))
    );
  }
}