import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { EnvLoaderService } from '@services/env-loader.service';

/** Prefixes relative request URLs with the API base URL from env.json. */
export const apiPrefixInterceptor: HttpInterceptorFn = (req, next) => {
  const apiUrl = inject(EnvLoaderService).apiUrl();
  if (apiUrl && !/^(https?:)?\/\//i.test(req.url) && !req.url.startsWith(apiUrl)) {
    req = req.clone({ url: apiUrl + req.url });
  }
  return next(req);
};
