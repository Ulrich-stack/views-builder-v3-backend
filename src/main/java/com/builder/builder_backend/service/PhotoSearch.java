package com.builder.builder_backend.service;

import java.util.List;

/** Une banque de photos libres de droits interrogée par mots-clés (Unsplash, Pexels, Openverse...). */
public interface PhotoSearch {

    /** Forme de l'emplacement à remplir : la banque privilégie les photos qui s'y cadrent bien. */
    enum Orientation { LANDSCAPE, PORTRAIT, SQUARE, ANY }

    /**
     * Une photo trouvée.
     * @param url       taille courante, pour une image dans la page
     * @param large     grande taille, pour une photo de fond pleine largeur
     * @param credit    crédit à afficher (« Nom sur Unsplash »), ou null
     * @param creditUrl lien vers la page du photographe, ou null
     * @param trackUrl  adresse à appeler quand la photo est utilisée (exigé par Unsplash), ou null
     */
    record Photo(String url, String large, String credit, String creditUrl, String trackUrl) {
        public Photo(String url, String large) {
            this(url, large, null, null, null);
        }
    }

    /** Photos correspondant à la requête, les plus pertinentes d'abord ; liste vide si rien n'est trouvé ou en cas d'erreur. */
    List<Photo> search(String query, Orientation orientation);

    /** Signale à la banque qu'une photo a été placée dans une page (sans effet par défaut). */
    default void markUsed(Photo photo) {
    }
}
